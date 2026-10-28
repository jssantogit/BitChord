package com.music.bitchord.data.catalog

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountDraft
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCredentialStore
import com.music.bitchord.data.catalog.provider.opensubsonic.openSubsonicProviderKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalLibraryAvailabilityTest {

    @Test
    fun `catalog without eligible recording returns empty list`() = runTest {
        val fixture = fixture()

        assertTrue(fixture.availability.availableRecordingIds().isEmpty())
    }

    @Test
    fun `active local asset makes recording eligible`() = runTest {
        val fixture = fixture()
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "recording-local")

        assertEquals(listOf("recording-local"), fixture.availability.availableRecordingIds())
    }

    @Test
    fun `OpenSubsonic asset is eligible while account and credential are configured`() = runTest {
        val fixture = fixture()
        fixture.configureOpenSubsonic()
        fixture.store.addAsset(openSubsonicProviderKey(ACCOUNT_ID), "recording-remote")

        assertEquals(listOf("recording-remote"), fixture.availability.availableRecordingIds())
    }

    @Test
    fun `forgotten OpenSubsonic connection makes remote-only recording ineligible without deleting history`() = runTest {
        val fixture = fixture()
        fixture.configureOpenSubsonic()
        fixture.store.addAsset(openSubsonicProviderKey(ACCOUNT_ID), "recording-remote")
        assertEquals(listOf("recording-remote"), fixture.availability.availableRecordingIds())

        fixture.accountRepository.forget()

        assertTrue(fixture.availability.availableRecordingIds().isEmpty())
        assertEquals(listOf("recording-remote"), fixture.store.allRecordingIds())
    }

    @Test
    fun `forgotten remote source does not remove recording that still has active local asset`() = runTest {
        val fixture = fixture()
        fixture.configureOpenSubsonic()
        fixture.store.addAsset(openSubsonicProviderKey(ACCOUNT_ID), "recording-shared")
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "recording-shared")

        fixture.accountRepository.forget()

        assertEquals(listOf("recording-shared"), fixture.availability.availableRecordingIds())
    }

    @Test
    fun `temporary remote health failure does not remove configured source and availability has no network dependency`() = runTest {
        val fixture = fixture()
        fixture.configureOpenSubsonic()
        fixture.store.addAsset(openSubsonicProviderKey(ACCOUNT_ID), "recording-offline-server")
        fixture.accountRepository.recordHealthFailure(atEpochSeconds = 123L)

        assertEquals(listOf("recording-offline-server"), fixture.availability.availableRecordingIds())
        assertFalse(
            OpenSubsonicLibraryProviderConfigurationReader::class.java.declaredFields.any { field ->
                val type = field.type.name
                type.contains("Transport") || type.contains("HttpClient") || type.contains("RemoteCatalogProvider")
            },
        )
        assertEquals(1, fixture.store.readCount)
    }

    @Test
    fun `multiple active assets of same recording collapse to one canonical id`() = runTest {
        val fixture = fixture()
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "recording-one")
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "recording-one")

        assertEquals(listOf("recording-one"), fixture.availability.availableRecordingIds())
    }

    @Test
    fun `two active providers for same recording still return one candidate identity`() = runTest {
        val fixture = fixture()
        fixture.configureOpenSubsonic()
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "recording-shared")
        fixture.store.addAsset(openSubsonicProviderKey(ACCOUNT_ID), "recording-shared")

        assertEquals(listOf("recording-shared"), fixture.availability.availableRecordingIds())
    }

    @Test
    fun `historical canonical recording without usable MediaAsset is excluded`() = runTest {
        val fixture = fixture()
        fixture.store.rememberRecording("recording-history-only")

        assertTrue(fixture.availability.availableRecordingIds().isEmpty())
        assertEquals(listOf("recording-history-only"), fixture.store.allRecordingIds())
    }

    @Test
    fun `availability result is deterministic and defensively deduplicated`() = runTest {
        val fixture = fixture()
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "recording-z")
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "recording-a")
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "recording-z")
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "recording-m")

        assertEquals(
            listOf("recording-a", "recording-m", "recording-z"),
            fixture.availability.availableRecordingIds(),
        )
    }

    @Test
    fun `account metadata without readable credential is not an active remote source`() = runTest {
        val fixture = fixture()
        fixture.configureOpenSubsonic()
        fixture.store.addAsset(openSubsonicProviderKey(ACCOUNT_ID), "recording-remote")
        fixture.credentials.clear(ACCOUNT_ID)

        assertTrue(fixture.availability.availableRecordingIds().isEmpty())
    }

    private fun fixture(): Fixture {
        val persistence = FakeAccountPersistence()
        val credentials = FakeCredentialStore()
        val accountRepository = OpenSubsonicAccountRepository(
            persistence = persistence,
            credentials = credentials,
            idFactory = { ACCOUNT_ID },
        )
        val store = FakeAvailabilityStore()
        val availability = CanonicalLibraryAvailabilityRepository(
            store = store,
            providerConfiguration = OpenSubsonicLibraryProviderConfigurationReader(accountRepository),
        )
        return Fixture(store, credentials, accountRepository, availability)
    }

    private data class Fixture(
        val store: FakeAvailabilityStore,
        val credentials: FakeCredentialStore,
        val accountRepository: OpenSubsonicAccountRepository,
        val availability: CanonicalLibraryAvailabilityReader,
    ) {
        fun configureOpenSubsonic() {
            accountRepository.save(
                OpenSubsonicAccountDraft(
                    serverUrl = "https://music.example.test",
                    authMode = OpenSubsonicAuthMode.API_KEY,
                    secretReplacement = "fixture-key",
                ),
            )
        }
    }

    private class FakeAvailabilityStore : CanonicalLibraryAvailabilityStore {
        private val recordings = linkedSetOf<String>()
        private val assets = mutableListOf<Pair<String, String>>()
        var readCount = 0
            private set

        fun rememberRecording(recordingId: String) {
            recordings += recordingId
        }

        fun addAsset(providerKey: String, recordingId: String) {
            recordings += recordingId
            assets += providerKey to recordingId
        }

        fun allRecordingIds(): List<String> = recordings.sorted()

        override suspend fun recordingIdsForProviders(providerKeys: List<String>): List<String> {
            readCount += 1
            val active = providerKeys.toSet()
            return assets.filter { (provider, _) -> provider in active }.map { (_, recordingId) -> recordingId }
        }
    }

    private class FakeAccountPersistence : OpenSubsonicAccountPersistence {
        private var record: OpenSubsonicAccountRecord? = null
        override fun read(): OpenSubsonicAccountRecord? = record
        override fun write(record: OpenSubsonicAccountRecord) {
            this.record = record
        }
        override fun clear() {
            record = null
        }
    }

    private class FakeCredentialStore : OpenSubsonicCredentialStore {
        private val values = mutableMapOf<String, String>()
        override fun read(accountId: String): String? = values[accountId]
        override fun write(accountId: String, credential: String) {
            values[accountId] = credential
        }
        override fun clear(accountId: String) {
            values.remove(accountId)
        }
    }

    private companion object {
        const val ACCOUNT_ID = "home"
    }
    @Test fun scopedSourceProjectionNeverIncludesForgottenRemoteAccount() = runTest {
        val fixture = fixture()
        val remoteProvider = openSubsonicProviderKey(ACCOUNT_ID)
        fixture.configureOpenSubsonic()
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "local")
        fixture.store.addAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "both")
        fixture.store.addAsset(remoteProvider, "remote")
        fixture.store.addAsset(remoteProvider, "both")
        val sources = fixture.availability.availableRecordingSources()
            .associateBy { it.recordingId }
        assertEquals(setOf(CanonicalLibrarySourceKind.LOCAL), sources["local"]?.kinds)
        assertEquals(setOf(CanonicalLibrarySourceKind.OPENSUBSONIC), sources["remote"]?.kinds)
        assertEquals(setOf(
            CanonicalLibrarySourceKind.LOCAL, CanonicalLibrarySourceKind.OPENSUBSONIC,
        ), sources["both"]?.kinds)

        fixture.accountRepository.forget()
        val forgotten = fixture.availability.availableRecordingSources()
            .associateBy { it.recordingId }
        assertEquals(setOf("both", "local"), forgotten.keys)
        assertEquals(setOf(CanonicalLibrarySourceKind.LOCAL), forgotten["both"]?.kinds)
        assertEquals(listOf("both", "local"), fixture.availability.availableRecordingIds())
    }

    @Test fun sourceProjectionExcludesRemoteWithoutReadableCredential() = runTest {
        val fixture = fixture()
        fixture.configureOpenSubsonic()
        fixture.store.addAsset(openSubsonicProviderKey(ACCOUNT_ID), "remote")
        fixture.credentials.clear(ACCOUNT_ID)
        assertTrue(fixture.availability.availableRecordingSources().isEmpty())
    }

}
