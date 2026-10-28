package com.music.bitchord.data.catalog

import android.content.Context
import com.music.bitchord.data.catalog.provider.opensubsonic.AndroidKeystoreOpenSubsonicCredentialStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.openSubsonicProviderKey

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
)

internal fun interface CanonicalLibraryAvailabilityReader {
    suspend fun availableRecordingIds(): List<String>

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
}

/**
 * Catalog owns eligibility; Recommendation receives only deterministic canonical IDs.
 */
internal class CanonicalLibraryAvailabilityRepository(
    private val store: CanonicalLibraryAvailabilityStore,
    private val providerConfiguration: CanonicalLibraryProviderConfigurationReader,
) : CanonicalLibraryAvailabilityReader {
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
            // A future/unknown configured provider cannot be mistaken for
            // OpenSubsonic, nor can a known local copy prove NOT remote when
            // another available copy has unclassified provenance.
            CanonicalAvailableRecordingSources(
                id, if (id in unclassifiedRecordings) emptySet() else kinds.toSet(),
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
