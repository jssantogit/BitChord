package com.music.bitchord.data.providers

import android.content.Context
import android.os.Build
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.catalog.provider.opensubsonic.AndroidKeystoreOpenSubsonicCredentialStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.flow.StateFlow

/**
 * Installed early in Android startup, fail-closed.
 *
 * Registers the full built-in provider inventory (honestly marking unimplemented
 * capabilities as NOT_IMPLEMENTED rather than READY) and exposes both the
 * admission [ProviderPolicyGate] and the single-authority [ProviderAuthorityCoordinator].
 */
internal object ProviderRuntime {
    @Volatile private var session: ProviderCapabilitySession? = null
    @Volatile private var coordinator: ProviderAuthorityCoordinator? = null
    @Volatile private var installedInProcess: Boolean = false

    private val plainJvmTestGate: ProviderPolicyGate by lazy {
        val mb = BuiltInProviderKeys.MUSICBRAINZ
        val caa = BuiltInProviderKeys.COVER_ART_ARCHIVE
        val appleArt = BuiltInProviderKeys.APPLE_ARTIST_ART
        val itunes = BuiltInProviderKeys.ITUNES
        val discogs = BuiltInProviderKeys.DISCOGS
        val listenbrainz = BuiltInProviderKeys.LISTENBRAINZ
        val mbCaps = setOf(
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.IDENTITY,
        )
        ProviderPolicyGate(
            ProviderCapabilityPolicy(
                descriptors = listOf(
                    ProviderDescriptor(mb, mbCaps, ProviderReadiness.READY),
                    ProviderDescriptor(caa, setOf(ProviderCapability.RELEASE_ARTWORK), ProviderReadiness.READY),
                    ProviderDescriptor(appleArt, setOf(ProviderCapability.ARTIST_ARTWORK), ProviderReadiness.READY),
                    BuiltInProviderDescriptors.itunes(ProviderReadiness.READY),
                    BuiltInProviderDescriptors.discogs(tokenConfigured = true, implemented = true),
                    ProviderDescriptor(listenbrainz, setOf(
                        ProviderCapability.PUBLIC_PLAYLISTS,
                        ProviderCapability.CHART_MOST_PLAYED,
                        ProviderCapability.PERSONAL_RECOMMENDATIONS,
                    ), ProviderReadiness.READY),
                ),
                choices = mbCaps.associateWith { capability ->
                    listOf(ProviderChoice(mb, enabled = true, order = 0)) +
                        if (capability == ProviderCapability.IDENTITY) emptyList()
                        else listOf(ProviderChoice(itunes, enabled = true, order = 1))
                } + mapOf(
                    ProviderCapability.RELEASE_ARTWORK to listOf(
                        ProviderChoice(caa, enabled = true, order = 0),
                        ProviderChoice(itunes, enabled = true, order = 1),
                    ),
                    ProviderCapability.ARTIST_ARTWORK to listOf(ProviderChoice(appleArt, enabled = true, order = 0)),
                    ProviderCapability.EDITION_METADATA to listOf(ProviderChoice(discogs, enabled = true, order = 0)),
                    ProviderCapability.PERSONAL_RECOMMENDATIONS to listOf(
                        ProviderChoice(listenbrainz, enabled = true, order = 0)),
                    ProviderCapability.PUBLIC_PLAYLISTS to listOf(ProviderChoice(listenbrainz, enabled = true, order = 0)),
                    ProviderCapability.CHART_MOST_PLAYED to listOf(ProviderChoice(listenbrainz, enabled = true, order = 0)),
                ),
            ),
        )
    }

    fun isAndroidEnvironment(): Boolean = installedInProcess || runCatching {
        Build.VERSION.SDK_INT != 0
    }.getOrDefault(false)

    private fun installedDescriptors(sources: List<SourceConfig>): List<ProviderDescriptor> =
        ProviderCapabilityRegistry.descriptorsFrom(
            ProviderCapabilityRegistry.inventory(sources = sources),
        )

    /** Source identity only. This must be evaluated AFTER SourceRegistry.init. */
    fun extensionDescriptors(sources: List<SourceConfig>): List<ProviderDescriptor> =
        sources.asSequence()
            .filter { it.kind == SourceKind.ADDON || it.kind == SourceKind.CUSTOM_MODULE }
            .distinctBy(SourceConfig::id)
            .map { config ->
                ProviderDescriptor(
                    key = ProviderKey(
                        providerId = if (config.kind == SourceKind.ADDON) "addon"
                            else "custom_module",
                        instanceId = config.id,
                    ),
                    capabilities = setOf(ProviderCapability.EXTENSION_PLAYBACK),
                    readiness = ProviderReadiness.READY,
                )
            }
            .toList()

    fun install(context: Context) {
        installedInProcess = true
        if (session != null) return
        synchronized(this) {
            if (session != null) return
            val prefs = context.applicationContext.getSharedPreferences(
                "bit_chord_provider_capabilities", Context.MODE_PRIVATE,
            )
            val created = ProviderCapabilitySession(
                store = ProviderPreferenceStore(prefs),
                initialDescriptors = installedDescriptors(emptyList()),
            )
            session = created
            val appContext = context.applicationContext
            val accounts = OpenSubsonicAccountRepository(
                persistence = SharedPrefsOpenSubsonicAccountPersistence(appContext),
                credentials = AndroidKeystoreOpenSubsonicCredentialStore(appContext),
            )
            coordinator = ProviderAuthorityCoordinator(created) {
                accounts.current()?.let { record ->
                    OpenSubsonicProviderAccountState(
                        accountId = record.accountId,
                        credentialAvailable = accounts.hasCredential(),
                    )
                }
            }
        }
    }

    fun gateOrNull(): ProviderPolicyGate? = session?.gate

    /** Missing policy in Android must not authorize source-owned cached reads. */
    fun gateForCacheReadOrNull(): ProviderPolicyGate? =
        if (isAndroidEnvironment()) session?.gate else plainJvmTestGate

    fun sessionOrNull(): ProviderCapabilitySession? = session

    fun authorityOrNull(): ProviderAuthorityCoordinator? = coordinator

    val state: StateFlow<ProviderSessionSnapshot>?
        get() = session?.state

    /**
     * Run from the startup initialization worker, AFTER SourceRegistry.init.
     * Preserve the original MusicBrainz access policy once and register the
     * restored extension identities, without migrating any active legacy
     * lyrics/addon toggle while its old setter remains the real writer.
     */
    suspend fun migrateMusicBrainzLegacyAccess(): Boolean {
        val current = session ?: return false
        current.refreshDescriptors(installedDescriptors(SourceRegistry.configs.value))
        return current.initialize(musicBrainzBaseline())
    }

    /** No lyric or extension defaults may be persisted by a startup migration. */
    fun musicBrainzBaseline(): Map<ProviderCapability, List<ProviderChoice>> {
        val mb = ProviderKey("musicbrainz")
        return listOf(
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.IDENTITY,
        ).associateWith { listOf(ProviderChoice(mb, enabled = true, order = 0)) }
    }

    /**
     * Legacy synchronous Settings UI bridges. Null means startup migration is
     * not yet complete (the old preference store remains the input for import).
     * False means a durable canonical write failed: never silently dual-write.
     * Settings switches are infrequent; IO itself runs on Dispatchers.IO.
     */
    fun updateLyricsFromLegacyUi(
        order: List<LyricsSource>,
        enabled: Set<LyricsSource>,
    ): Boolean? {
        val current = session ?: return null
        if (current.state.value.status == ProviderSessionStatus.BOOTING) return null
        val authority = coordinator ?: return false
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            authority.setLyricsPreferences(order, enabled)
        }
    }

    fun updateExtensionFromLegacyUi(
        configId: String,
        enabled: Boolean,
        sources: List<SourceConfig>,
    ): Boolean? {
        val current = session ?: return null
        if (current.state.value.status == ProviderSessionStatus.BOOTING) return null
        val authority = coordinator ?: return false
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            authority.setExtensionEnabled(configId, enabled, sources)
        }
    }

    /** Persisted OpenSubsonic account state only; never override canonical opt-outs. */
    fun refreshOpenSubsonicAccountReadiness(): Boolean? {
        val current = session ?: return null
        if (current.state.value.status == ProviderSessionStatus.BOOTING) return null
        val authority = coordinator ?: return false
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            authority.onOpenSubsonicAccountChanged()
        }
    }

    /** Login/logout changes only Soulseek prerequisite readiness. */
    fun refreshSoulseekAccountReadiness(configured: Boolean): Boolean? {
        val current = session ?: return null
        if (current.state.value.status == ProviderSessionStatus.BOOTING) return null
        val authority = coordinator ?: return false
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            authority.updateSoulseekReadiness(configured)
            true
        }
    }

    fun updateListeningFromLegacyUi(
        lastfmEnabled: Boolean,
        lastfmScrobbleEnabled: Boolean,
        listenBrainzEnabled: Boolean,
    ): Boolean? = synchronizeListening(
        lastfmEnabled && lastfmScrobbleEnabled, listenBrainzEnabled,
        updateChoices = true,
    )

    /** Login/logout only changes readiness; it must not override user opt-outs. */
    fun refreshListeningCredentialsFromLegacyUi(): Boolean? = synchronizeListening(
        AppSettings.lastfmEnabled.value && AppSettings.lastfmScrobbleEnabled.value,
        AppSettings.listenBrainzEnabled.value,
        updateChoices = false,
    )

    private fun synchronizeListening(
        lastfmEnabled: Boolean,
        listenBrainzEnabled: Boolean,
        updateChoices: Boolean,
    ): Boolean? {
        val current = session ?: return null
        if (current.state.value.status == ProviderSessionStatus.BOOTING) return null
        val authority = coordinator ?: return false
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            authority.updateListeningSettings(
                lastfmConfigured = AppSettings.lastfmSessionKey.value.isNotBlank() &&
                    AppSettings.lastfmApiKey.value.isNotBlank(),
                listenBrainzConfigured = AppSettings.listenBrainzToken.value.isNotBlank(),
                lastfmEnabled = lastfmEnabled,
                listenBrainzEnabled = listenBrainzEnabled,
                updateChoices = updateChoices,
            )
        }
    }

    suspend fun setChoices(
        capability: ProviderCapability,
        choices: List<ProviderChoice>,
    ): Boolean = session?.update(capability, choices) ?: false
}

/** Denial is not a transient error and must never trigger a secret fallback. */
internal class ProviderRequestDeniedException(
    val capability: ProviderCapability,
    val providerKey: ProviderKey,
) : IllegalStateException("Provider request not permitted: $providerKey / $capability")
