package com.music.bitchord.data.catalog

import android.content.Context
import com.music.bitchord.data.catalog.provider.opensubsonic.AndroidKeystoreOpenSubsonicCredentialStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.openSubsonicProviderKey
import kotlinx.serialization.json.Json

/**
 * Provider-neutral Catalog -> Recommendation seam for Library Mode.
 *
 * Only canonical Recording identity crosses this boundary. MediaAsset/provider
 * details remain Catalog implementation details used solely to decide current
 * availability.
 */
internal enum class CanonicalLibrarySourceKind { LOCAL, OPENSUBSONIC }

/**
 * Provider-scoped, canonical-only source projection. Never exposes provider ID,
 * secret, URI, native sourceKey or media asset ID to Listening or the UI.
 */
internal data class CanonicalAvailableRecordingSources(
    val recordingId: String,
    val kinds: Set<CanonicalLibrarySourceKind>,
    /** False when any currently eligible copy uses an unclassified provider. */
    val kindsComplete: Boolean = true,
)

/** Safe provider-aggregated metadata: canonical ID and tag values only. */
internal data class CanonicalAvailableRecordingTags(
    val recordingId: String,
    val genres: List<String> = emptyList(),
    val genreEvidenceUnknown: Boolean = true,
    val moods: List<String> = emptyList(),
    val moodEvidenceUnknown: Boolean = true,
    val bpms: List<Double> = emptyList(),
    val bpmEvidenceUnknown: Boolean = true,
)

internal fun interface CanonicalLibraryAvailabilityReader {
    suspend fun availableRecordingIds(): List<String>

    /** Old readers cannot prove remote tags, so their default stays unknown. */
    suspend fun availableRecordingTags(
        recordingIds: List<String>,
    ): List<CanonicalAvailableRecordingTags> = emptyList()

    /**
     * Old injected readers do not have reliable source provenance. The default
     * is UNKNOWN rather than fabricating a local/remote claim.
     */
    suspend fun availableRecordingSources(): List<CanonicalAvailableRecordingSources> =
        emptyList()
}

/** Catalog-internal read projection used by the availability repository. */
internal fun interface CanonicalLibraryAvailabilityStore {
    suspend fun recordingIdsForProviders(providerKeys: List<String>): List<String>

    /** Current provider assets only; tag presence never leaves Catalog as an ID. */
    suspend fun tagsForProvider(
        providerKey: String, recordingIds: List<String>,
    ): List<CanonicalScopedTagRow> = emptyList()
}

/** Catalog-internal configuration view. This is not exposed to Recommendation. */
internal fun interface CanonicalLibraryProviderConfigurationReader {
    fun configuredProviderKeys(): Set<String>
}

/**
 * Current configured sources for Library Mode.
 *
 * Local MediaStore is usable while its reconciled MediaAsset exists. An
 * OpenSubsonic source is usable when the saved account can produce a config,
 * which requires both account metadata and a readable credential. Persisted
 * health status is deliberately ignored: transient server/network failure is
 * not a reason to erase Library eligibility.
 */
internal class OpenSubsonicLibraryProviderConfigurationReader(
    private val accountRepository: OpenSubsonicAccountRepository,
) : CanonicalLibraryProviderConfigurationReader {
    override fun configuredProviderKeys(): Set<String> = buildSet {
        add(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER)
        accountRepository.savedConfig()?.let { config ->
            add(openSubsonicProviderKey(config))
        }
    }
}

/**
 * Room-backed availability projection. It deliberately reuses existing Catalog
 * provider rows rather than introducing provider-native identity into this seam.
 */
internal class RoomCanonicalLibraryAvailabilityStore(
    private val dao: CanonicalCatalogDao,
) : CanonicalLibraryAvailabilityStore {
    override suspend fun recordingIdsForProviders(providerKeys: List<String>): List<String> =
        providerKeys.flatMap { providerKey ->
            dao.availableRecordingIdsForProvider(providerKey)
        }

    override suspend fun tagsForProvider(
        providerKey: String, recordingIds: List<String>,
    ): List<CanonicalScopedTagRow> =
        dao.providerScopedTagRows(providerKey, recordingIds)
}

/**
 * Catalog owns eligibility; Recommendation receives only deterministic canonical IDs.
 */
private fun parseAvailableTagList(raw: String?): List<String>? =
    raw?.let {
        runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull()
            ?.map(String::trim)
            ?.takeIf { tags -> tags.all(String::isNotBlank) }
            ?.distinct()
    }

private fun parseAvailableBpms(raw: String?): List<Double>? =
    raw?.let {
        runCatching { Json.decodeFromString<List<Double>>(it) }.getOrNull()
            ?.takeIf { values -> values.all { v -> v.isFinite() && v > 0.0 && v <= 999.0 } }
            ?.distinct()
    }

internal class CanonicalLibraryAvailabilityRepository(
    private val store: CanonicalLibraryAvailabilityStore,
    private val providerConfiguration: CanonicalLibraryProviderConfigurationReader,
) : CanonicalLibraryAvailabilityReader {
    /**
     * Return only tags associated with the currently configured accounts.
     * An unclassified/local asset remains UNKNOWN for genre/mood, but an
     * OpenSubsonic positive tag is usable even alongside unknown copies.
     */
    override suspend fun availableRecordingTags(
        recordingIds: List<String>,
    ): List<CanonicalAvailableRecordingTags> {
        val ids = recordingIds.distinct()
        require(ids.all(String::isNotBlank))
        if (ids.isEmpty()) return emptyList()
        val configured = providerConfiguration.configuredProviderKeys().toList().sorted()
        require(configured.all(String::isNotBlank))
        data class MutableTagEvidence(
            val genres: MutableSet<String> = linkedSetOf(),
            val moods: MutableSet<String> = linkedSetOf(),
            val bpms: MutableSet<Double> = linkedSetOf(),
            var genreUnknown: Boolean = false,
            var moodUnknown: Boolean = false,
            var bpmUnknown: Boolean = false,
            var hasAsset: Boolean = false,
        )
        val byRecording = ids.associateWith { MutableTagEvidence() }
        for (provider in configured) {
            for (batch in ids.chunked(400)) {
                for (row in store.tagsForProvider(provider, batch)) {
                    val target = byRecording[row.recordingId] ?: continue
                    target.hasAsset = true
                    val genres = parseAvailableTagList(row.genresJson)
                    val moods = parseAvailableTagList(row.moodsJson)
                    val bpms = parseAvailableBpms(row.bpmsJson)
                    if (genres == null) target.genreUnknown = true else target.genres.addAll(genres)
                    if (moods == null) target.moodUnknown = true else target.moods.addAll(moods)
                    if (bpms == null) target.bpmUnknown = true else target.bpms.addAll(bpms)
                }
            }
        }
        return ids.map { id ->
            val value = byRecording.getValue(id)
            CanonicalAvailableRecordingTags(
                recordingId = id,
                genres = value.genres.toList(),
                moods = value.moods.toList(),
                genreEvidenceUnknown = !value.hasAsset || value.genreUnknown,
                moodEvidenceUnknown = !value.hasAsset || value.moodUnknown,
                bpms = value.bpms.toList(),
                bpmEvidenceUnknown = !value.hasAsset || value.bpmUnknown,
            )
        }
    }

    override suspend fun availableRecordingSources(): List<CanonicalAvailableRecordingSources> {
        // Read configured provider identity at the request boundary. Only the
        // current account's verified provider key is eligible for remote claims.
        val configured = providerConfiguration.configuredProviderKeys().toList().sorted()
        require(configured.all { it.isNotBlank() })
        val evidence = sortedMapOf<String, MutableSet<CanonicalLibrarySourceKind>>()
        val unclassifiedRecordings = hashSetOf<String>()
        for (provider in configured) {
            val kind = when {
                provider == CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER ->
                    CanonicalLibrarySourceKind.LOCAL
                provider.startsWith("opensubsonic:") &&
                    provider.substringAfter(':').isNotBlank() ->
                    CanonicalLibrarySourceKind.OPENSUBSONIC
                else -> null
            }
            store.recordingIdsForProviders(listOf(provider)).forEach { id ->
                require(id.isNotBlank()) { "Canonical recordingId must not be blank" }
                val kinds = evidence.getOrPut(id) { linkedSetOf() }
                if (kind == null) unclassifiedRecordings += id else kinds.add(kind)
            }
        }
        return evidence.map { (id, kinds) ->
            // Preserve verified positive copies even if a future provider is
            // unclassified; incomplete coverage cannot prove a negative.
            CanonicalAvailableRecordingSources(
                id, kinds.toSet(), kindsComplete = id !in unclassifiedRecordings,
            )
        }
    }

    override suspend fun availableRecordingIds(): List<String> {
        val providerKeys = providerConfiguration.configuredProviderKeys()
            .asSequence()
            .onEach { providerKey ->
                require(providerKey.isNotBlank()) { "Configured provider key must not be blank" }
            }
            .distinct()
            .sorted()
            .toList()
        if (providerKeys.isEmpty()) return emptyList()

        return store.recordingIdsForProviders(providerKeys)
            .asSequence()
            .onEach { recordingId ->
                require(recordingId.isNotBlank()) { "Canonical recordingId must not be blank" }
            }
            .distinct()
            .sorted()
            .toList()
    }
}

/** Production composition; account/config reads are local-only and perform no network call. */
internal object CanonicalLibraryAvailabilityReaders {
    fun persistent(context: Context): CanonicalLibraryAvailabilityReader {
        val appContext = context.applicationContext
        val accountRepository = OpenSubsonicAccountRepository(
            persistence = SharedPrefsOpenSubsonicAccountPersistence(appContext),
            credentials = AndroidKeystoreOpenSubsonicCredentialStore(appContext),
        )
        return CanonicalLibraryAvailabilityRepository(
            store = RoomCanonicalLibraryAvailabilityStore(
                CanonicalCatalogDatabase.get(appContext).catalogDao(),
            ),
            providerConfiguration = OpenSubsonicLibraryProviderConfigurationReader(accountRepository),
        )
    }
}
