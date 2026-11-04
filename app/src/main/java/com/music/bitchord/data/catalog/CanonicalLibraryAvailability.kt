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
internal fun interface CanonicalLibraryAvailabilityReader {
    suspend fun availableRecordingIds(): List<String>
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
            dao.providerRows(providerKey).map { row -> row.recordingId }
        }
}

/**
 * Catalog owns eligibility; Recommendation receives only deterministic canonical IDs.
 */
internal class CanonicalLibraryAvailabilityRepository(
    private val store: CanonicalLibraryAvailabilityStore,
    private val providerConfiguration: CanonicalLibraryProviderConfigurationReader,
) : CanonicalLibraryAvailabilityReader {
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
