package com.music.bitchord

import com.music.bitchord.data.catalog.provider.ProviderCapabilities
import com.music.bitchord.data.catalog.provider.ProviderHealth
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountController
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountDraft
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountOperations
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicConnectionReport
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCredentialStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.openSubsonicProviderKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenSubsonicAccountControllerTest {

    @Test
    fun `first save generates stable account id and edits keep it`() {
        val plain = FakeAccountPersistence()
        val secrets = FakeCredentialStore()
        val ids = ArrayDeque(listOf("account-one", "account-two"))
        val repository = OpenSubsonicAccountRepository(plain, secrets) { ids.removeFirst() }

        val first = repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = " https://music.example.test/ ",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "first-key",
            ),
        )
        val edited = repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://music.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = null,
            ),
        )

        assertEquals("account-one", first.accountId)
        assertEquals(first.accountId, edited.accountId)
        assertEquals("https://music.example.test", edited.serverUrl)
        assertEquals("first-key", secrets.read(first.accountId))
    }

    @Test
    fun `plain account record never contains or echoes credential`() {
        val plain = FakeAccountPersistence()
        val secrets = FakeCredentialStore()
        val repository = OpenSubsonicAccountRepository(plain, secrets) { "home" }
        val secret = "super-secret-api-key"

        val saved = repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://music.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = secret,
            ),
        )

        assertTrue(repository.hasCredential())
        assertEquals(secret, secrets.read(saved.accountId))
        assertFalse(saved.toString().contains(secret))
        assertFalse(plain.record.toString().contains(secret))
    }

    @Test
    fun `changing auth mode requires replacement credential`() {
        val repository = OpenSubsonicAccountRepository(
            FakeAccountPersistence(),
            FakeCredentialStore(),
        ) { "home" }
        repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://music.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "api-key",
            ),
        )

        try {
            repository.save(
                OpenSubsonicAccountDraft(
                    serverUrl = "https://music.example.test",
                    authMode = OpenSubsonicAuthMode.PASSWORD,
                    username = "joao",
                    secretReplacement = null,
                ),
            )
            fail("Expected auth-mode change without a new secret to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.orEmpty().contains("api-key"))
        }
    }

    @Test
    fun `changing server requires replacement credential but preserves account id`() {
        val plain = FakeAccountPersistence()
        val secrets = FakeCredentialStore()
        val repository = OpenSubsonicAccountRepository(plain, secrets) { "home" }
        val first = repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://music.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "old-key",
            ),
        )

        try {
            repository.save(
                OpenSubsonicAccountDraft(
                    serverUrl = "https://other.example.test",
                    authMode = OpenSubsonicAuthMode.API_KEY,
                    secretReplacement = null,
                ),
            )
            fail("Expected server change without a new secret to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.orEmpty().contains("old-key"))
        }

        val moved = repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://other.example.test/",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "new-key",
            ),
        )
        assertEquals(first.accountId, moved.accountId)
        assertEquals("https://other.example.test", moved.serverUrl)
        assertEquals("new-key", secrets.read(first.accountId))
    }

    @Test
    fun `saved config reconstructs password auth without storing password in record`() {
        val repository = OpenSubsonicAccountRepository(
            FakeAccountPersistence(),
            FakeCredentialStore(),
        ) { "home" }
        repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://music.example.test/",
                authMode = OpenSubsonicAuthMode.PASSWORD,
                username = " listener ",
                secretReplacement = "password with spaces ",
            ),
        )

        val config = repository.savedConfig()
        assertNotNull(config)
        val resolved = config!!
        assertEquals("home", resolved.accountId)
        assertEquals("https://music.example.test", resolved.normalizedServerUrl)
        val auth = resolved.auth as OpenSubsonicAuth.Password
        assertEquals("listener", auth.username)
        assertEquals("password with spaces ", auth.password)
        assertFalse(repository.current()!!.toString().contains(auth.password))
    }

    @Test
    fun `test connection does not run catalog sync and sanitizes thrown errors`() = runTest {
        val repository = configuredRepository()
        val operations = FakeOperations(
            testFailure = IllegalStateException("https://host/rest/ping?apiKey=LEAK"),
        )
        val controller = OpenSubsonicAccountController(repository, operations) { 123L }

        val result = controller.testConnection(
            OpenSubsonicAccountDraft(
                serverUrl = "https://music.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = null,
            ),
        )

        assertTrue(result.isFailure)
        assertEquals("OpenSubsonic connection failed", result.exceptionOrNull()?.message)
        assertEquals(1, operations.testCalls)
        assertEquals(0, operations.syncCalls)
        assertNull(repository.current()!!.lastSyncAtEpochSeconds)
    }

    @Test
    fun `successful manual sync records safe summary after completed attempt`() = runTest {
        val repository = configuredRepository()
        val operations = FakeOperations(
            syncResult = ProviderSyncResult.Success(
                syncedAssets = 42,
                removedAssets = 3,
                identityConflicts = 1,
            ),
        )
        val controller = OpenSubsonicAccountController(repository, operations) { 456L }

        val result = controller.syncNow()

        assertTrue(result is ProviderSyncResult.Success)
        assertEquals(1, operations.syncCalls)
        val account = repository.current()!!
        assertEquals(456L, account.lastSyncAtEpochSeconds)
        assertEquals(true, account.lastSyncSucceeded)
        assertEquals("Synced 42 tracks · removed 3 · 1 identity conflict", account.lastSyncSummary)
    }

    @Test
    fun `failed manual sync never persists provider exception text`() = runTest {
        val repository = configuredRepository()
        val operations = FakeOperations(
            syncResult = ProviderSyncResult.Failure("request failed apiKey=LEAK"),
        )
        val controller = OpenSubsonicAccountController(repository, operations) { 789L }

        val result = controller.syncNow()

        assertTrue(result is ProviderSyncResult.Failure)
        val account = repository.current()!!
        assertEquals(789L, account.lastSyncAtEpochSeconds)
        assertEquals(false, account.lastSyncSucceeded)
        assertEquals("Sync failed", account.lastSyncSummary)
        assertFalse(account.lastSyncSummary.orEmpty().contains("LEAK"))
    }

    @Test
    fun `forgetting connection clears credential and a later setup gets new id`() {
        val plain = FakeAccountPersistence()
        val secrets = FakeCredentialStore()
        val ids = ArrayDeque(listOf("one", "two"))
        val repository = OpenSubsonicAccountRepository(plain, secrets) { ids.removeFirst() }

        val first = repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://one.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "key-one",
            ),
        )
        repository.forget()
        val second = repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://two.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "key-two",
            ),
        )

        assertNull(secrets.read(first.accountId))
        assertNotEquals(first.accountId, second.accountId)
    }

    @Test
    fun `saved connection success after server rotation must not publish old health`() = runTest {
        val repository = configuredRepository()
        val operations = object : OpenSubsonicAccountOperations {
            override suspend fun test(provider: OpenSubsonicProvider): OpenSubsonicConnectionReport {
                repository.save(
                    OpenSubsonicAccountDraft(
                        serverUrl = "https://different.example.test",
                        authMode = OpenSubsonicAuthMode.API_KEY,
                        secretReplacement = "new-server-secret",
                    ),
                )
                return OpenSubsonicConnectionReport(
                    health = ProviderHealth(
                        reachable = true,
                        serverType = "navidrome",
                        serverVersion = "1.0",
                        protocolVersion = "1.16.1",
                        openSubsonic = true,
                    ),
                    capabilities = null,
                )
            }

            override suspend fun sync(provider: OpenSubsonicProvider): ProviderSyncResult =
                error("Connection test must not sync")
        }
        val controller = OpenSubsonicAccountController(repository, operations) { 456L }

        val result = controller.testSavedConnection()

        assertTrue(result.isFailure)
        assertEquals("https://different.example.test", repository.current()?.serverUrl)
        assertNull(repository.current()?.lastHealthAtEpochSeconds)
        assertNull(repository.current()?.lastHealthReachable)
    }

    @Test
    fun `failed old connection after credential rotation must not mark new account offline`() = runTest {
        val repository = configuredRepository()
        val operations = object : OpenSubsonicAccountOperations {
            override suspend fun test(provider: OpenSubsonicProvider): OpenSubsonicConnectionReport {
                repository.save(
                    OpenSubsonicAccountDraft(
                        serverUrl = "https://music.example.test",
                        authMode = OpenSubsonicAuthMode.API_KEY,
                        secretReplacement = "rotated-secret",
                    ),
                )
                error("https://music.example.test/rest/ping.view?apiKey=old-secret")
            }

            override suspend fun sync(provider: OpenSubsonicProvider): ProviderSyncResult =
                error("Connection test must not sync")
        }
        val controller = OpenSubsonicAccountController(repository, operations) { 789L }

        val result = controller.testSavedConnection()

        assertTrue(result.isFailure)
        assertEquals("OpenSubsonic connection failed", result.exceptionOrNull()?.message)
        assertNull(repository.current()?.lastHealthAtEpochSeconds)
        assertNull(repository.current()?.lastHealthReachable)
        assertEquals("rotated-secret", (repository.savedConfig()?.auth as OpenSubsonicAuth.ApiKey).value)
    }

    @Test
    fun `server or credential change clears stale account health and sync status`() {
        val repository = configuredRepository()
        repository.recordHealth(
            ProviderHealth(
                reachable = true,
                serverType = "navidrome",
                serverVersion = "1.0",
                protocolVersion = "1.16.1",
                openSubsonic = true,
            ),
            123L,
        )
        repository.recordSync(ProviderSyncResult.Success(42, 0, 0), 124L)

        val rotated = repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://different.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "different-secret",
            ),
        )

        assertEquals("home", rotated.accountId)
        assertNull(rotated.lastHealthAtEpochSeconds)
        assertNull(rotated.lastHealthReachable)
        assertNull(rotated.lastServerType)
        assertNull(rotated.lastServerVersion)
        assertNull(rotated.lastSyncAtEpochSeconds)
        assertNull(rotated.lastSyncSucceeded)
        assertNull(rotated.lastSyncSummary)

        repository.recordHealth(
            ProviderHealth(
                reachable = true,
                serverType = "navidrome",
                serverVersion = "2.0",
                protocolVersion = "1.16.1",
                openSubsonic = true,
            ),
            200L,
        )
        val credentialRotated = repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://different.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "third-secret",
            ),
        )
        assertNull(credentialRotated.lastHealthAtEpochSeconds)
        assertNull(credentialRotated.lastHealthReachable)
    }

    @Test
    fun `authenticated catalog realm survives no-op edits but rotates on credential and server edits`() {
        val accounts = configuredRepository()
        val first = accounts.current()!!
        val keyA = openSubsonicProviderKey(accounts.savedConfig()!!)
        assertTrue(keyA.startsWith("opensubsonic:home"))
        accounts.save(OpenSubsonicAccountDraft(
            serverUrl = "https://music.example.test",
            authMode = OpenSubsonicAuthMode.API_KEY,
        ))
        assertEquals(keyA, openSubsonicProviderKey(accounts.savedConfig()!!))

        accounts.save(OpenSubsonicAccountDraft(
            serverUrl = "https://music.example.test",
            authMode = OpenSubsonicAuthMode.API_KEY,
            secretReplacement = "new-auth-realm",
        ))
        val keyB = openSubsonicProviderKey(accounts.savedConfig()!!)
        assertNotEquals(keyA, keyB)
        accounts.save(OpenSubsonicAccountDraft(
            serverUrl = "https://elsewhere.example.test",
            authMode = OpenSubsonicAuthMode.API_KEY,
            secretReplacement = "elsewhere",
        ))
        assertNotEquals(keyB, openSubsonicProviderKey(accounts.savedConfig()!!))
        accounts.save(OpenSubsonicAccountDraft(
            serverUrl = "https://music.example.test",
            authMode = OpenSubsonicAuthMode.API_KEY,
            secretReplacement = "new-auth-realm",
        ))
        // A -> B -> A cannot make stale remote assets from A current.
        assertNotEquals(keyB, openSubsonicProviderKey(accounts.savedConfig()!!))
        assertEquals(first.accountId, accounts.current()!!.accountId)
    }

    @Test
    fun `failed realm metadata commit must not leave old catalog authorized with new credential`() {
        var stored: OpenSubsonicAccountRecord? = null
        var denyNext = false
        val persistence = object : OpenSubsonicAccountPersistence {
            override fun read(): OpenSubsonicAccountRecord? = stored
            override fun write(record: OpenSubsonicAccountRecord) {
                if (denyNext) error("encrypted preference disk unavailable")
                stored = record
            }
            override fun clear() { stored = null }
        }
        val credentials = FakeCredentialStore()
        val repository = OpenSubsonicAccountRepository(persistence, credentials) { "home" }
        repository.save(OpenSubsonicAccountDraft(
            "https://music.example.test", OpenSubsonicAuthMode.API_KEY,
            secretReplacement = "old-secret",
        ))
        val oldProvider = openSubsonicProviderKey(repository.savedConfig()!!)
        denyNext = true
        val failed = runCatching {
            repository.save(OpenSubsonicAccountDraft(
                "https://another.example.test", OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "new-secret",
            ))
        }
        assertTrue(failed.isFailure)
        assertEquals("https://music.example.test", stored?.serverUrl)
        assertEquals(oldProvider, openSubsonicProviderKey(
            com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig(
                "home", "https://music.example.test",
                OpenSubsonicAuth.ApiKey("old-secret"),
                catalogRealmId = stored?.catalogRealmId.orEmpty(),
            )))
        // If the old metadata survives, its credential must not become the new
        // server's credential; require explicit reauthentication instead.
        assertNull(repository.savedConfig())
        assertNull(credentials.read("home"))
    }

    private fun configuredRepository(): OpenSubsonicAccountRepository =
        OpenSubsonicAccountRepository(
            FakeAccountPersistence(),
            FakeCredentialStore(),
        ) { "home" }.also { repository ->
            repository.save(
                OpenSubsonicAccountDraft(
                    serverUrl = "https://music.example.test",
                    authMode = OpenSubsonicAuthMode.API_KEY,
                    secretReplacement = "secret",
                ),
            )
        }

    private class FakeAccountPersistence : OpenSubsonicAccountPersistence {
        var record: OpenSubsonicAccountRecord? = null

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

    private class FakeOperations(
        private val testFailure: Throwable? = null,
        private val syncResult: ProviderSyncResult = ProviderSyncResult.Success(0, 0, 0),
    ) : OpenSubsonicAccountOperations {
        var testCalls = 0
        var syncCalls = 0

        override suspend fun test(provider: OpenSubsonicProvider): OpenSubsonicConnectionReport {
            testCalls += 1
            testFailure?.let { throw it }
            return OpenSubsonicConnectionReport(
                health = ProviderHealth(
                    reachable = true,
                    serverType = "navidrome",
                    serverVersion = "1.0",
                    protocolVersion = "1.16.1",
                    openSubsonic = true,
                ),
                capabilities = ProviderCapabilities(
                    librarySync = true,
                    healthCheck = true,
                    directPlay = true,
                    serverTranscoding = true,
                    apiKeyAuthentication = true,
                ),
            )
        }

        override suspend fun sync(provider: OpenSubsonicProvider): ProviderSyncResult {
            syncCalls += 1
            return syncResult
        }
    }
}
