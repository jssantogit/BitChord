package com.music.bitchord.data.sources

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.data.providers.ProviderSessionStatus
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.permits
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonException
import com.music.bitchord.data.sources.addon.DetectedFormat
import com.music.bitchord.data.sources.addon.SourceFormats
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.playback.audio.LosslessOutput
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * One configured source: which protocol, which index.
 *
 * Stored in encrypted prefs — [baseUrl] is a module index the user called
 * "for my private use", not something to leave sitting in plain-text
 * SharedPreferences on a device someone else might get into.
 */
@Serializable
data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val kind: SourceKind,
    /** What the user called it. Blank falls back to the server's host, or the kind's own label. */
    val label: String = "",
    val baseUrl: String = "",
    /** JioSaavn is opt-in; retired YouTube must never be enabled by default. */
    val enabled: Boolean = kind != SourceKind.JIOSAAVN && kind != SourceKind.YOUTUBE,
    /**
     * The addon's `allowDownloads`, as its manifest last said. Stored rather
     * than asked each time so the answer is there without a request — see
     * [checkValidLossless] for why that matters.
     */
    val allowDownloads: Boolean = true,
    /**
     * The addon's `checkValidLossless`, as its manifest last said. Has to be
     * stored: the promise is that a gated addon is not contacted at all while
     * no lossless output is connected, and asking it for its manifest would
     * already break that. Refreshed whenever the manifest is next read.
     */
    val checkValidLossless: Boolean = false,
    /** Stored cache generation; rotate when source endpoint/identity changes. */
    val playbackCacheEpoch: String = UUID.randomUUID().toString(),
) {
    /** Whether this source is shut out right now because it needs an output that isn't connected. */
    val awaitsLosslessOutput: Boolean
        get() = checkValidLossless && !LosslessOutput.capable

    /** The two manifest switches, without the parts of the config the user set. */
    internal fun withPolicyOf(other: SourceConfig) =
        copy(allowDownloads = other.allowDownloads, checkValidLossless = other.checkValidLossless)

    /** What the sources screen and the player show. Never blank. */
    val displayName: String
        get() = label.ifBlank {
            baseUrl.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                ?: kind.label
        }

    /** Whether this has enough filled in to be worth contacting at all. */
    val isComplete: Boolean
        get() = !kind.needsServer || baseUrl.isNotBlank()
}

/**
 * Configured Android audio sources: user-provided addons and opt-in JioSaavn.
 *
 * Retired YouTube entries can still be decoded from old preference files, but
 * are removed during initialization and are never offered in source settings,
 * instantiated, or selected for playback and downloads.
 */
object SourceRegistry {

    private const val TAG = "BitChord"

    private lateinit var prefs: SharedPreferences

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Every configured source, enabled or not. */
    val configs = MutableStateFlow<List<SourceConfig>>(emptyList())

    /**
     * Built instances, keyed by config id, rebuilt whenever [configs] changes.
     *
     * Held rather than constructed per call so that a source with any warmed
     * state — a module whose index has already been fetched — keeps it across
     * tracks instead of re-probing on every resolve.
     */
    private var instances: Map<String, MusicSource> = emptyMap()
    /** Independent download producer scopes: playback revocation must never
     * cancel an otherwise authorized download from this same addon instance. */
    private var addonDownloadInstances: Map<String, AddonSource> = emptyMap()
    private var moduleDownloadInstances: Map<String, ModuleSource> = emptyMap()

    fun init(context: Context) {
        // Same repair and degradation as AuthStore; see EncryptedPrefs.
        prefs = EncryptedPrefs.open(context, "bitchord_sources", "bitchord_sources_plain")

        val rawSources = prefs.getString(KEY_SOURCES, null)
        val stored = rawSources?.let(::decodeStored) ?: emptyList()
        // Default constructor values cannot persist across restarts unless
        // a historical preference without this field is migrated once.
        val missingEpoch = rawSources?.let { raw ->
            runCatching {
                json.parseToJsonElement(raw).jsonArray.any { element ->
                    (element as? JsonObject)?.let { known ->
                        !known.containsKey("playbackCacheEpoch")
                    } ?: false
                }
            }.getOrDefault(false)
        } ?: false
        val jioOptInMigrationDone = prefs.getBoolean(KEY_JIOSAAVN_OPT_IN_V1, false)
        val after = sourcesForInit(stored, forceJioSaavnOff = !jioOptInMigrationDone)

        // Commit source migration and the retired-source opt-out marker as
        // ONE durable transaction before constructing runtime sources.
        // Failed writes cannot advertise a configuration/epoch that will be
        // different after the next process start.
        if (after != stored || !jioOptInMigrationDone || missingEpoch) {
            val preserved = sourcesDocumentPreservingUnknown(rawSources, after)
            val committed = sourceDocumentCommitAllowed(preserved) { document ->
                prefs.edit()
                    .putString(KEY_SOURCES, document)
                    .putBoolean(KEY_JIOSAAVN_OPT_IN_V1, true)
                    .commit()
            }
            if (!committed) {
                TrackLog.w(TAG, "source migration not durable; keeping sources closed")
                publish(emptyList(), persist = false)
                return
            }
        }
        publish(after, persist = false)
    }

    /**
     * Built-in seeding and one-time source migrations, kept pure for tests.
     *
     * [forceJioSaavnOff] is true exactly once for every install that first runs
     * this version, including upgrades whose stored config currently says on.
     * Once the marker is written, a user who deliberately enables JioSaavn is
     * left enabled on subsequent launches.
     */
    internal fun sourcesForInit(
        stored: List<SourceConfig>,
        forceJioSaavnOff: Boolean,
    ): List<SourceConfig> {
        // JioSaavn, YouTube and the obsolete built-in module are retired
        // network backends. Keep historical track identities elsewhere, but
        // never seed or restore these source configurations as runnable
        // providers. User-defined addons and custom modules remain untouched.
        return stored.filterNot {
            it.kind == SourceKind.JIOSAAVN ||
                it.kind == SourceKind.MODULE ||
                it.kind == SourceKind.YOUTUBE
        }
    }

    /**
     * Decodes a stored source list one entry at a time rather than as a
     * single list, so one entry naming a kind this build no longer has —
     * left over from before a kind was retired — doesn't take every other
     * entry down with it. A strict `List<SourceConfig>` decode fails whole:
     * one bad enum value and the user's real, working module config is
     * silently gone along with it.
     */
    private fun decodeStored(raw: String): List<SourceConfig> {
        val elements = runCatching { json.parseToJsonElement(raw).jsonArray }
            .getOrElse { return emptyList() }
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(SourceConfig.serializer(), element) }
                .onFailure { TrackLog.w(TAG, "dropping unreadable stored source: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * Return a lossless persisted source list with updated known entries and
     * verbatim unknown/future source types. Unknown fields on known entries
     * survive too. A corrupt top-level document is left untouched (null);
     * we cannot safely repair it by replacing it with an empty list.
     *
     * A returned document never activates unknown kinds: runtime projection
     * still decodes only supported [SourceConfig] entries.
     */
    internal fun sourcesDocumentPreservingUnknown(
        raw: String?,
        active: List<SourceConfig>,
    ): String? {
        if (raw == null)
            return json.encodeToString(ListSerializer(SourceConfig.serializer()), active)
        val elements = runCatching { json.parseToJsonElement(raw).jsonArray }
            .getOrNull() ?: return null
        // Duplicate decodable IDs cannot be losslessly merged: do not
        // silently collapse distinct user rows under one shared ID.
        val decodedIds = elements.mapNotNull { element ->
            runCatching {
                json.decodeFromJsonElement(SourceConfig.serializer(), element).id
            }.getOrNull()
        }
        if (decodedIds.size != decodedIds.toSet().size ||
            active.map(SourceConfig::id).distinct().size != active.size) return null
        val activeById = active.associateBy(SourceConfig::id)
        val serializedIds = mutableSetOf<String>()
        // Future schema fields may use a non-primitive id. Those malformed
        // rows must remain inert and preserved, not crash application start.
        val reservedIds = elements.mapNotNull { element ->
            runCatching {
                (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }.toSet()
        // A future/unknown row may reserve the SAME id that this build is
        // trying to publish as a known source. Silently skipping that active
        // config after a successful commit would create a non-durable runtime
        // identity. Refuse the ambiguous write and retain the original bytes.
        val unknownIds = elements.mapNotNull { element ->
            val known = runCatching {
                json.decodeFromJsonElement(SourceConfig.serializer(), element)
            }.getOrNull()
            if (known != null) return@mapNotNull null
            runCatching {
                (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }.toSet()
        if (active.any { it.id in unknownIds }) return null
        val preserved = elements.mapNotNull { element ->
            val decoded = runCatching {
                json.decodeFromJsonElement(SourceConfig.serializer(), element)
            }.getOrNull() ?: return@mapNotNull element // Never discard future types.
            val replacement = activeById[decoded.id] ?: return@mapNotNull null
            if (!serializedIds.add(decoded.id)) return@mapNotNull null
            val serialized = json.encodeToJsonElement(
                SourceConfig.serializer(), replacement,
            ).jsonObject
            // Keep additional fields a future release may have introduced.
            JsonObject((element as? JsonObject).orEmpty() + serialized)
        }.toMutableList()
        active.filterNot { it.id in serializedIds || it.id in reservedIds }
            .forEach { config ->
                preserved += json.encodeToJsonElement(SourceConfig.serializer(), config)
            }
        return JsonArray(preserved).toString()
    }

    /**
     * A failed disk write must never permit publication of a new source
     * endpoint or cache generation. This runs before the state/instance swap.
     * Unknown documents and exceptions are fail-closed; their bytes are
     * preserved for a later version or recovery.
     */
    internal fun sourceDocumentCommitAllowed(
        document: String?,
        write: (String) -> Boolean,
    ): Boolean = document != null && runCatching { write(document) }.getOrDefault(false)

    /**
     * The enabled sources, module first and YouTube last, however they're stored.
     *
     * The user's standing choice and nothing else. Both playback and downloads
     * start here, so disabling JioSaavn or an addon excludes it from both. A
     * stream is budgeted further by [activeForPlayback]; downloads deliberately
     * apply no connection-quality ceiling — see [SourceResolver.forDownload].
     */
    fun active(): List<MusicSource> =
        orderCandidatesByCapability(
            audioCandidateConfigs(configs.value)
                // Never probe a lossless-only addon without a capable output.
                .filterNot { it.awaitsLosslessOutput },
            ProviderRuntime.gateOrNull(),
            ProviderCapability.EXTENSION_PLAYBACK,
        ).mapNotNull { instances[it.id] }

    /**
     * [active], minus the addons whose manifest said `allowDownloads: 0`. A
     * download walks this instead, so the next source in line serves it.
     */
    fun activeForDownload(): List<MusicSource> {
        val gate = ProviderRuntime.gateOrNull()
        return orderCandidatesByCapability(
            downloadCandidateConfigs(configs.value, gate), gate, ProviderCapability.DOWNLOAD,
        ).mapNotNull { source ->
            addonDownloadInstances[source.id] ?:
                moduleDownloadInstances[source.id] ?: instances[source.id]
        }
    }

    /**
     * The canonical session owns priority independently for every capability.
     * Never let the legacy SourceKind rank or stored playback order override a
     * different (DOWNLOAD × instance) selection. Equal/unconfigured priorities
     * preserve the existing stable kind + stored ordering.
     */
    internal fun orderCandidatesByCapability(
        candidates: List<SourceConfig>,
        gate: ProviderPolicyGate?,
        capability: ProviderCapability,
    ): List<SourceConfig> {
        val priorities = gate?.ordered(capability)
            ?.mapIndexed { index, descriptor -> descriptor.key to index }
            ?.toMap().orEmpty()
        return candidates.withIndex().sortedWith(
            compareBy<IndexedValue<SourceConfig>> { indexed ->
                val config = indexed.value
                val key = ProviderKey(
                    if (config.kind == SourceKind.ADDON) "addon" else "custom_module",
                    config.id,
                )
                priorities[key] ?: Int.MAX_VALUE
            }.thenBy { it.value.kind.rank }
                .thenBy { it.index },
        ).map { it.value }
    }

    /**
     * Download consent is independent from playback consent. Do not start with
     * active(), which already removes EXTENSION_PLAYBACK-disabled addons.
     * Legacy enabled remains authoritative for ordinary (non-addon) sources,
     * but only the canonical DOWNLOAD ticket admits addon/custom-module work.
     */
    internal fun downloadCandidateConfigs(
        sources: List<SourceConfig>,
        gate: ProviderPolicyGate?,
    ): List<SourceConfig> = downloadEligibleConfigs(
        sources.filter { source ->
            source.isComplete && !source.awaitsLosslessOutput &&
                source.kind != SourceKind.YOUTUBE &&
                source.kind != SourceKind.JIOSAAVN &&
                source.kind != SourceKind.MODULE &&
                (source.enabled ||
                    source.kind == SourceKind.ADDON ||
                    source.kind == SourceKind.CUSTOM_MODULE)
        },
        gate,
    )

    /** DOWNLOAD and EXTENSION_PLAYBACK use independent per-instance tickets. */
    internal fun downloadEligibleConfigs(
        sources: List<SourceConfig>,
        gate: ProviderPolicyGate?,
    ): List<SourceConfig> = sources.filter { source ->
        if (!source.allowDownloads) return@filter false
        when (source.kind) {
            SourceKind.ADDON, SourceKind.CUSTOM_MODULE -> {
                val key = ProviderKey(
                    if (source.kind == SourceKind.ADDON) "addon" else "custom_module",
                    source.id,
                )
                gate?.open(ProviderCapability.DOWNLOAD, key) != null
            }
            else -> true
        }
    }

    /**
     * Records what an addon's manifest said about downloads and lossless
     * output, if it changed.
     *
     * Does not rebuild the source the way an edit does — see [configuredBy] —
     * because nothing about where it points has changed, and this is called
     * from inside that source's own requests.
     */
    fun updatePolicy(configId: String, allowDownloads: Boolean, checkValidLossless: Boolean) {
        val current = config(configId) ?: return
        if (current.allowDownloads == allowDownloads && current.checkValidLossless == checkValidLossless) return
        TrackLog.d(
            TAG,
            "${current.displayName}: manifest now says allowDownloads=$allowDownloads " +
                "checkValidLossless=$checkValidLossless",
        )
        publish(
            configs.value.map {
                if (it.id == configId) it.copy(allowDownloads = allowDownloads, checkValidLossless = checkValidLossless) else it
            },
        )
    }

    /** The common eligibility gate used by playback and download source walks. */
    internal fun enabledConfigs(configs: List<SourceConfig>): List<SourceConfig> {
        val status = ProviderRuntime.state?.value?.status
        val projected = if (status == ProviderSessionStatus.READY ||
            status == ProviderSessionStatus.PERSISTING ||
            status == ProviderSessionStatus.STORAGE_ERROR
        ) {
            val extensions = ProviderRuntime.authorityOrNull()?.currentExtensionsProjection(configs)
            if (extensions == null) configs else extensions + configs.filterNot {
                it.kind == SourceKind.ADDON || it.kind == SourceKind.CUSTOM_MODULE
            }
        } else configs
        return extensionEligibleConfigs(
            projected.filter { it.enabled && it.isComplete && it.kind != SourceKind.YOUTUBE &&
                it.kind != SourceKind.JIOSAAVN },
            ProviderRuntime.gateOrNull(),
        )
    }

    /** A legacy enabled flag is never sufficient authorization to contact
     * an addon. Enforce the exact instance's EXTENSION_PLAYBACK ticket at
     * selection time, including during startup/storage failure. */
    internal fun extensionEligibleConfigs(
        sources: List<SourceConfig>,
        gate: ProviderPolicyGate?,
    ): List<SourceConfig> = sources.filter { source ->
        when (source.kind) {
            SourceKind.ADDON, SourceKind.CUSTOM_MODULE -> {
                val key = ProviderKey(
                    if (source.kind == SourceKind.ADDON) "addon" else "custom_module",
                    source.id,
                )
                gate?.open(ProviderCapability.EXTENSION_PLAYBACK, key) != null
            }
            else -> true
        }
    }

    /** User-visible source selection is provider-neutral. Retired YouTube
     * settings stay decoded for migration but cannot appear as an option. */
    internal fun displayConfigs(configs: List<SourceConfig>): List<SourceConfig> =
        configs.filterNot { it.kind == SourceKind.YOUTUBE || it.kind == SourceKind.JIOSAAVN }

    /** An additional safety gate for legacy configs passed in by older callers. */
    internal fun audioCandidateConfigs(configs: List<SourceConfig>): List<SourceConfig> =
        enabledConfigs(configs).filterNot {
            it.kind == SourceKind.YOUTUBE || it.kind == SourceKind.JIOSAAVN
        }

    /**
     * [active], minus the sources the ceiling on the connection in hand does
     * not pay for — see [AudioQuality.permits].
     *
     * Every path that starts or plans a *stream* asks this rather than
     * [active], so the Wi-Fi and mobile-data rungs stay two independent
     * answers to "what does this minute cost" and switching networks switches
     * between them. Downloads deliberately keep asking [active]: what a saved
     * file is worth is [DownloadQuality][com.music.bitchord.data.settings.DownloadQuality]'s
     * question, and when it may be fetched is
     * [AppSettings.wifiOnlyDownloads][com.music.bitchord.data.settings.AppSettings.wifiOnlyDownloads]'s.
     */
    fun activeForPlayback(): List<MusicSource> {
        val ceiling = AppSettings.effectiveAudioQuality
        return active().filter { ceiling.permits(it.kind) }
    }

    fun instance(configId: String): MusicSource? = instances[configId]

    fun config(configId: String): SourceConfig? = configs.value.firstOrNull { it.id == configId }

    /**
     * Drops completed addon track answers for an explicit quality retry.
     *
     * Empty responses are normally legitimate and cached, but an addon whose
     * upstream was temporarily down can express that outage as HTTP 200 with
     * no rows. The listener pressing "Upgrade quality" is an explicit request
     * to ask again now, not to repeat that cached answer or a previously issued
     * stream URL. In-flight calls stay shared so a quick second press cannot
     * duplicate work already on the wire.
     */
    fun clearCompletedAddonTrackCalls() {
        instances.values
            .filterIsInstance<AddonSource>()
            .forEach(AddonSource::clearCompletedTrackCalls)
    }

    // ── Editing ─────────────────────────────────────────────────────────

    /** Retired kinds are readable for migration, never editable audio sources. */
    fun add(config: SourceConfig) {
        if (config.kind == SourceKind.YOUTUBE || config.kind == SourceKind.MODULE) return
        publish(configs.value + config.tidied())
    }

    fun update(config: SourceConfig) {
        if (config.kind == SourceKind.YOUTUBE || config.kind == SourceKind.MODULE) return
        val tidied = config.tidied()
        publish(configs.value.map { if (it.id == tidied.id) tidied else it })
    }

    /**
     * The config as it should be stored, rather than as it was typed.
     *
     * Only addons have anything to tidy, and only their URL: the two forms
     * people paste — the addon's root and its `manifest.json` — address the
     * same server, and storing them as typed makes two configs that behave
     * identically look different on screen and compare unequal in
     * [configuredBy], which would rebuild a warm source over a cosmetic edit.
     * Normalising here rather than in the editor keeps it true for every caller
     * and not just the one with a text field.
     */
    private fun SourceConfig.tidied(): SourceConfig =
        if (kind == SourceKind.ADDON) copy(baseUrl = AddonClient.normalizeBase(baseUrl)) else this

    fun remove(configId: String) {
        val target = config(configId) ?: return
        if (target.kind in BUILT_IN_KINDS) return
        publish(configs.value.filterNot { it.id == configId })
    }

    /** No retired source can be re-enabled through saved settings or callers. */
    fun setEnabled(configId: String, enabled: Boolean) {
        val current = config(configId) ?: return
        if (current.kind == SourceKind.YOUTUBE || current.kind == SourceKind.JIOSAAVN) return
        if (current.kind == SourceKind.ADDON || current.kind == SourceKind.CUSTOM_MODULE) {
            val committed = ProviderRuntime.updateExtensionFromLegacyUi(configId, enabled, configs.value)
            if (committed == false) return
            publish(configs.value.map { if (it.id == configId) it.copy(enabled = enabled) else it },
                persist = committed != true)
        } else {
            publish(configs.value.map { if (it.id == configId) it.copy(enabled = enabled) else it })
        }
    }

    /**
     * Backend removal gate. A legacy videoId is an archival locator, not
     * permission to request YouTube audio. Retain this method temporarily
     * while migrating the Media3 loader and persisted source configuration.
     * No configuration (including a previously enabled YouTube switch)
     * can reactivate network requests to the retiring backend.
     */
    internal fun mayResolveLegacyYoutubeStream(
        @Suppress("UNUSED_PARAMETER") configs: List<SourceConfig>,
    ): Boolean = false

    /**
     * Puts the addons in [orderedIds], which is the order they will be asked in.
     *
     * The stored list *is* the priority order and needs no rank field to carry
     * it: [active] sorts by [SourceKind.ordinal] and Kotlin's sort is stable,
     * so two sources of the same kind keep the order they are held in here.
     * Writing a rank alongside would be a second source of truth for a fact the
     * list already states, and the two would drift the first time one was
     * written without the other.
     *
     * Ids that name nothing are dropped and addons the caller forgot to mention
     * are appended, so a list that has moved on since the drag started — an
     * addon removed on another screen, say — reorders what it can rather than
     * deleting the rest. Everything that is not an addon keeps its place, since
     * its rank is decided by its kind and is not the user's to set.
     */
    fun reorderAddons(orderedIds: List<String>) {
        val addons = configs.value.filter { it.kind.isUserAdded }
        if (addons.size < 2) return
        val byId = addons.associateBy { it.id }
        val moved = orderedIds.mapNotNull(byId::get)
        val missed = addons.filterNot { config -> moved.any { it.id == config.id } }
        val reordered = moved + missed
        if (reordered.map { it.id } == addons.map { it.id }) return
        val status = ProviderRuntime.state?.value?.status
        val canonicalActive = status == ProviderSessionStatus.READY ||
            status == ProviderSessionStatus.PERSISTING
        if (canonicalActive) {
            val committed = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                ProviderRuntime.authorityOrNull()?.reorderExtensions(
                    reordered.map { it.id }, configs.value,
                ) == true
            }
            if (!committed) return
        }
        publish(reordered + configs.value.filterNot { it.kind.isUserAdded },
            persist = !canonicalActive)
    }

    /** Read-only projection: never writes a second preference authority. */
    internal fun applyProviderProjection(next: List<SourceConfig>) {
        publish(next, persist = false)
    }

    /** Preserve across projections/toggles; rotate for endpoint edits, even edit-back. */
    internal fun scopeAfterSourceUpdate(old: SourceConfig?, next: SourceConfig): SourceConfig =
        when {
            old == null -> next
            old.kind == next.kind && old.baseUrl == next.baseUrl ->
                next.copy(playbackCacheEpoch = old.playbackCacheEpoch)
            else -> next.copy(playbackCacheEpoch = UUID.randomUUID().toString())
        }

    private fun publish(next: List<SourceConfig>, persist: Boolean = true) {
        // The persisted migration is not the only entry point: a stale editor,
        // legacy projection or restored snapshot can publish a source list.
        // Never construct or persist a retired backend through this path.
        val previousConfigs = configs.value.associateBy(SourceConfig::id)
        val activeConfigs = next.filterNot {
            it.kind == SourceKind.JIOSAAVN || it.kind == SourceKind.YOUTUBE ||
                it.kind == SourceKind.MODULE
        }.map { item -> scopeAfterSourceUpdate(previousConfigs[item.id], item) }
        // Do not erase a corrupt preference document while trying to
        // persist an unrelated edit. Avoid publishing an in-memory state
        // that cannot be persisted across process death.
        val storedDocument = if (persist && ::prefs.isInitialized)
            sourcesDocumentPreservingUnknown(
                prefs.getString(KEY_SOURCES, null), activeConfigs,
            )
        else null
        if (persist && ::prefs.isInitialized &&
            !sourceDocumentCommitAllowed(storedDocument) { document ->
                prefs.edit().putString(KEY_SOURCES, document).commit()
            }
        ) {
            TrackLog.w(TAG, "source update refused: preferences not durably committed")
            return
        }
        configs.value = activeConfigs
        // Rebuilt against the previous map so that an untouched source keeps
        // the instance it already had, rather than being replaced by an
        // identical-but-cold one every time an unrelated row is toggled.
        val previous = instances
        instances = activeConfigs.associate { config ->
            val existing = previous[config.id]?.takeIf { it.configuredBy(config) }
            config.id to (existing ?: build(config))
        }
        // Whatever the rebuild above left behind, told so. An addon that was
        // edited or removed is holding a manifest, a set of search answers and
        // a handful of stream URLs that all describe the server it used to
        // point at, and its client's own scope keeps them alive whether or not
        // anything still references the source. Serving one of those afterwards
        // would be answering a question about the old address with the new one
        // selected.
        previous.values.filterNot { it in instances.values }
            .forEach { source ->
                when (source) {
                    is AddonSource -> source.release()
                    is ModuleSource -> source.release()
                }
            }
        // The same addon can have DOWNLOAD enabled while EXTENSION_PLAYBACK is
        // disabled. Separate client scopes ensure revoking playback cancels
        // only playback HTTP and never the authorized download producer.
        val previousDownload = addonDownloadInstances
        addonDownloadInstances = activeConfigs
            .filter { it.kind == SourceKind.ADDON }
            .associate { config ->
                val existing = previousDownload[config.id]
                    ?.takeIf { it.config.withPolicyOf(config) == config }
                config.id to (existing ?: AddonSource(config, ProviderCapability.DOWNLOAD))
            }
        previousDownload.values.filterNot { it in addonDownloadInstances.values }
            .forEach { it.release() }
        val previousModules = moduleDownloadInstances
        moduleDownloadInstances = activeConfigs
            .filter { it.kind == SourceKind.CUSTOM_MODULE }
            .associate { config ->
                val existing = previousModules[config.id]
                    ?.takeIf { it.config.withPolicyOf(config) == config }
                config.id to (existing ?: ModuleSource(config, ProviderCapability.DOWNLOAD))
            }
        previousModules.values.filterNot { it in moduleDownloadInstances.values }
            .forEach { it.release() }
        if (persist && ProviderRuntime.state?.value?.status == ProviderSessionStatus.READY) {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                ProviderRuntime.authorityOrNull()?.onSourcesChanged(activeConfigs)
            }
        }
    }

    /**
     * Works out what is at [url] and returns a config that speaks to it, or a
     * refusal saying what was found instead.
     *
     * This is what makes the editor accept "any JSON" rather than one shape.
     * The kind is *detected* rather than chosen up front: pasting an addon's
     * root, an addon's `manifest.json`, or a module index all end here, and
     * which [MusicSource] gets built is decided by what the server actually
     * returned. Anything recognised but unplayable — an extension registry, a
     * manifest that cannot stream — comes back as a failure carrying a line
     * written for the person reading it.
     *
     * [existing] is carried through so editing a saved source keeps its id and
     * its on/off state; a new source gets a fresh config.
     */
    suspend fun identify(url: String, existing: SourceConfig? = null): Result<SourceConfig> {
        val detected = SourceFormats.identify(url).getOrElse { return Result.failure(it) }
        return when (detected) {
            is DetectedFormat.Addon -> Result.success(
                (existing ?: SourceConfig(kind = SourceKind.ADDON)).copy(
                    kind = SourceKind.ADDON,
                    baseUrl = detected.baseUrl,
                    label = detected.manifest.displayName,
                    allowDownloads = detected.manifest.downloadsAllowed,
                    checkValidLossless = detected.manifest.requiresLosslessOutput,
                ),
            )
            is DetectedFormat.ModuleIndex -> Result.success(
                (existing ?: SourceConfig(kind = SourceKind.CUSTOM_MODULE)).copy(
                    kind = SourceKind.CUSTOM_MODULE,
                    // The index URL itself, not a base: a module index is the
                    // document, where an addon's manifest only points at one.
                    baseUrl = detected.url,
                    label = existing?.label.orEmpty(),
                ),
            )
            is DetectedFormat.Unsupported -> Result.failure(AddonException(detected.reason))
        }
    }

    /**
     * The already-configured source pointing at [url], if there is one.
     *
     * Compared after [identify] has run rather than on the raw text, which is
     * what makes this catch the cases worth catching: an addon's root and its
     * `manifest.json` are the same server typed two ways, and both normalise to
     * one base before they reach here. A trailing slash, a `MANIFEST.JSON`, and
     * a host in a different case are all the same source too.
     *
     * [exceptId] is the source being edited, which is not its own duplicate —
     * without it, saving an existing addon without touching its URL would
     * refuse itself.
     */
    fun duplicateOf(url: String, exceptId: String? = null): SourceConfig? {
        val wanted = canonicalUrl(url)
        if (wanted.isEmpty()) return null
        return configs.value.firstOrNull { it.id != exceptId && canonicalUrl(it.baseUrl) == wanted }
    }

    /**
     * A URL reduced to the form two spellings of the same address share.
     *
     * Scheme and host are lowercased because they are case-insensitive; the
     * path deliberately is not, because on this protocol the path can carry a
     * user's token and two tokens differing only in case are two different
     * credentials. Falls back to the trimmed text when the URL will not parse,
     * so a malformed entry still compares equal to itself.
     */
    private fun canonicalUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        val parsed = trimmed.toHttpUrlOrNull() ?: return trimmed
        val path = parsed.encodedPath.trimEnd('/')
        val query = parsed.encodedQuery?.let { "?$it" }.orEmpty()
        return "${parsed.scheme}://${parsed.host}${if (parsed.port != defaultPort(parsed.scheme)) ":${parsed.port}" else ""}$path$query"
    }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    /**
     * Health-checks a config that hasn't been saved — what the editor's Test
     * button asks.
     *
     * Built fresh and thrown away rather than routed through [instances],
     * which hold the *stored* config: testing one of those would report on the
     * old address, which is precisely the state the user is in the middle of
     * correcting.
     */
    suspend fun probeCandidate(config: SourceConfig): SourceHealth {
        // Historical source rows remain deserializable, but cannot become
        // live YouTube clients through the editor's health-check endpoint.
        if (config.kind == SourceKind.YOUTUBE || config.kind == SourceKind.JIOSAAVN) {
            return SourceHealth.Rejected("This built-in audio source is retired")
        }
        return build(config).health()
    }

    private fun build(config: SourceConfig): MusicSource = when (config.kind) {
        SourceKind.ADDON -> AddonSource(config, ProviderCapability.EXTENSION_PLAYBACK)
        // Same protocol, same implementation — the kinds differ only in rank.
        SourceKind.CUSTOM_MODULE -> ModuleSource(config, ProviderCapability.EXTENSION_PLAYBACK)
        SourceKind.MODULE -> ModuleSource(config)
        SourceKind.JIOSAAVN -> error("Retired JioSaavn source cannot be constructed")
        // Stored legacy rows are deserialized but never turned into clients.
        SourceKind.YOUTUBE -> error("Retired YouTube source cannot be constructed")
    }

    /**
     * Whether an already-built instance still matches its stored config —
     * false after an edit that changes where it points, which is exactly when
     * the warm instance must be thrown away. The manifest switches are left
     * out: they change no address, and a source reads them live from here.
     */
    private fun MusicSource.configuredBy(config: SourceConfig): Boolean =
        this is ConfigBacked && this.config.withPolicyOf(config) == config

    /** Implemented by sources that carry their [SourceConfig], so [publish] can tell a real edit from a no-op. */
    internal interface ConfigBacked {
        val config: SourceConfig
    }

    // ── Track identity ──────────────────────────────────────────────────

    /**
     * A source-backed track's id, as it travels through the queue.
     *
     * Packed into the existing [Song.videoId][com.music.bitchord.data.model.Song.videoId]
     * rather than added beside it: that field is the app's media id everywhere —
     * the queue, the notification, the history, the like state — and a second
     * identity field would have to be threaded through every one of them, with
     * each place that forgot silently falling back to treating the track as
     * YouTube's.
     */
    fun trackKey(configId: String, trackId: String) = "$PREFIX$configId$SEPARATOR$trackId"

    /** The `(configId, trackId)` inside a [trackKey], or null if this is an ordinary YouTube id. */
    fun parseTrackKey(key: String): Pair<String, String>? = SourceTrackKeys.parse(key)

    /** The playback URI for a source-backed track; [PlaybackService] resolves it at open time. */
    fun trackUri(configId: String, trackId: String): String =
        Uri.Builder()
            .scheme("bitchord")
            .authority("source")
            .appendQueryParameter("s", configId)
            .appendQueryParameter("t", trackId)
            .build()
            .toString()

    private val BUILT_IN_KINDS = listOf(SourceKind.JIOSAAVN, SourceKind.YOUTUBE)

    private const val KEY_SOURCES = "sources"
    /** One-shot migration: existing users must explicitly opt in again. */
    private const val KEY_JIOSAAVN_OPT_IN_V1 = "jiosaavn_opt_in_v1"
    private const val PREFIX = "src:"
    private const val SEPARATOR = "::"
}
