package com.music.bitchord

import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountDraft
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCredentialStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccess
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessFailure
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAssetStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeProbeResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeProbeTransport
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicMediaAccessTest {

    @Test
    fun `known OpenSubsonic media asset resolves to ephemeral direct request`() = runTest {
        val fixture = fixture()

        val result = fixture.access.resolve("asset-canonical")

        val ready = result as OpenSubsonicMediaAccessResult.Ready
        assertEquals("recording-canonical", ready.request.recordingId)
        assertEquals("asset-canonical", ready.request.mediaAssetId)
        assertEquals("opensubsonic:home", ready.request.providerKey)
        assertEquals("https://music.example.test/rest/stream.view", ready.request.endpointUrl)
        assertTrue(ready.request.directOriginal)
    }

    @Test
    fun `recording media asset and provider track ids remain distinct roles`() = runTest {
        val fixture = fixture()

        val ready = fixture.access.resolve("asset-canonical") as OpenSubsonicMediaAccessResult.Ready
        val parameters = ready.request.materializeParameters()

        assertEquals("recording-canonical", ready.request.recordingId)
        assertEquals("asset-canonical", ready.request.mediaAssetId)
        assertEquals("server-track-native", parameters["id"])
        assertNotEquals(parameters["id"], ready.request.recordingId)
        assertNotEquals(parameters["id"], ready.request.mediaAssetId)
    }

    @Test
    fun `api key is read and materialized only at runtime`() = runTest {
        val fixture = fixture(secret = "runtime-api-key")
        assertEquals(0, fixture.credentials.readCalls)
        assertFalse(fixture.asset.uri.contains("runtime-api-key"))
        assertFalse(fixture.persistence.record.toString().contains("runtime-api-key"))

        val ready = fixture.access.resolve("asset-canonical") as OpenSubsonicMediaAccessResult.Ready
        assertEquals(1, fixture.credentials.readCalls)
        val parameters = ready.request.materializeParameters()

        assertEquals("runtime-api-key", parameters["apiKey"])
        assertFalse(ready.request.toString().contains("runtime-api-key"))
        assertFalse(ready.request.endpointUrl.contains("runtime-api-key"))
    }

    @Test
    fun `password auth materializes salted token at runtime without raw password`() = runTest {
        val fixture = fixture(
            authMode = OpenSubsonicAuthMode.PASSWORD,
            username = "listener",
            secret = "password with spaces ",
            salt = "abcdef123456",
        )
        assertEquals(0, fixture.credentials.readCalls)

        val ready = fixture.access.resolve("asset-canonical") as OpenSubsonicMediaAccessResult.Ready
        val parameters = ready.request.materializeParameters()

        assertEquals("listener", parameters["u"])
        assertEquals("abcdef123456", parameters["s"])
        assertEquals(md5("password with spaces abcdef123456"), parameters["t"])
        assertNull(parameters["p"])
        assertFalse(parameters.values.contains("password with spaces "))
        assertFalse(ready.request.toString().contains("password with spaces"))
    }

    @Test
    fun `no credential is present in persisted account or media asset state`() = runTest {
        val fixture = fixture(secret = "persist-me-never")

        val ready = fixture.access.resolve("asset-canonical") as OpenSubsonicMediaAccessResult.Ready

        assertFalse(fixture.persistence.record.toString().contains("persist-me-never"))
        assertFalse(fixture.asset.toString().contains("persist-me-never"))
        assertFalse(fixture.asset.uri.contains("persist-me-never"))
        assertFalse(ready.request.toString().contains("persist-me-never"))
        assertEquals("opensubsonic://asset/home/server-track-native", fixture.asset.uri)
    }

    @Test
    fun `forgotten account makes historical media asset unavailable`() = runTest {
        val fixture = fixture()
        fixture.repository.forget()

        val result = fixture.access.resolve("asset-canonical")

        assertEquals(
            OpenSubsonicMediaAccessFailure.ACCOUNT_NOT_CONFIGURED,
            (result as OpenSubsonicMediaAccessResult.Failure).reason,
        )
        assertTrue(fixture.store.assets.containsKey("asset-canonical"))
    }

    @Test
    fun `wrong provider and wrong OpenSubsonic account are rejected`() = runTest {
        val fixture = fixture()
        fixture.store.assets["local-asset"] = fixture.asset.copy(
            id = "local-asset",
            provider = "local-mediastore",
            sourceKey = "44",
            uri = "content://media/external/audio/media/44",
        )
        fixture.store.assets["other-account"] = fixture.asset.copy(
            id = "other-account",
            provider = "opensubsonic:other",
        )

        val local = fixture.access.resolve("local-asset") as OpenSubsonicMediaAccessResult.Failure
        val other = fixture.access.resolve("other-account") as OpenSubsonicMediaAccessResult.Failure

        assertEquals(OpenSubsonicMediaAccessFailure.UNSUPPORTED_PROVIDER, local.reason)
        assertEquals(OpenSubsonicMediaAccessFailure.ACCOUNT_MISMATCH, other.reason)
    }

    @Test
    fun `missing media asset fails without mutating catalog store`() = runTest {
        val fixture = fixture()
        val before = fixture.store.assets.toMap()

        val result = fixture.access.resolve("missing-asset")

        assertEquals(
            OpenSubsonicMediaAccessFailure.MEDIA_ASSET_NOT_FOUND,
            (result as OpenSubsonicMediaAccessResult.Failure).reason,
        )
        assertEquals(before, fixture.store.assets)
        assertEquals(0, fixture.store.writeCalls)
    }

    @Test
    fun `server change never reuses old credential against new host`() = runTest {
        val fixture = fixture(secret = "old-api-key")

        val rejected = runCatching {
            fixture.repository.save(
                OpenSubsonicAccountDraft(
                    serverUrl = "https://new.example.test",
                    authMode = OpenSubsonicAuthMode.API_KEY,
                    secretReplacement = null,
                ),
            )
        }
        assertTrue(rejected.isFailure)

        val stillOld = fixture.access.resolve("asset-canonical") as OpenSubsonicMediaAccessResult.Ready
        assertEquals("https://music.example.test/rest/stream.view", stillOld.request.endpointUrl)
        assertEquals("old-api-key", stillOld.request.materializeParameters()["apiKey"])

        fixture.repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://new.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "new-api-key",
            ),
        )
        val moved = fixture.access.resolve("asset-canonical") as OpenSubsonicMediaAccessResult.Ready
        val movedParameters = moved.request.materializeParameters()
        assertEquals("https://new.example.test/rest/stream.view", moved.request.endpointUrl)
        assertEquals("new-api-key", movedParameters["apiKey"])
        assertFalse(movedParameters.values.contains("old-api-key"))
    }

    @Test
    fun `network failure during runtime probe does not remove canonical media asset`() = runTest {
        val fixture = fixture()
        val before = fixture.store.assets.toMap()
        val request = (fixture.access.resolve("asset-canonical") as OpenSubsonicMediaAccessResult.Ready).request
        val transport = OpenSubsonicRuntimeProbeTransport { _, _ ->
            OpenSubsonicRuntimeProbeResult.NETWORK_UNAVAILABLE
        }

        val result = request.probe(transport)

        assertEquals(OpenSubsonicRuntimeProbeResult.NETWORK_UNAVAILABLE, result)
        assertEquals(before, fixture.store.assets)
        assertEquals(0, fixture.store.writeCalls)
    }

    @Test
    fun `direct original request uses raw stream and never enables transcoding parameters`() = runTest {
        val fixture = fixture()

        val request = (fixture.access.resolve("asset-canonical") as OpenSubsonicMediaAccessResult.Ready).request
        val parameters = request.materializeParameters()

        assertEquals("https://music.example.test/rest/stream.view", request.endpointUrl)
        assertEquals("raw", parameters["format"])
        assertEquals("server-track-native", parameters["id"])
        assertFalse("maxBitRate" in parameters)
        assertFalse("bitrate" in parameters)
        assertTrue(request.directOriginal)
    }

    @Test
    fun `blank provider locator is rejected safely`() = runTest {
        val fixture = fixture()
        fixture.store.assets["bad-locator"] = fixture.asset.copy(
            id = "bad-locator",
            sourceKey = "   ",
        )

        val result = fixture.access.resolve("bad-locator")

        assertEquals(
            OpenSubsonicMediaAccessFailure.INVALID_LOCATOR,
            (result as OpenSubsonicMediaAccessResult.Failure).reason,
        )
    }

    @Test
    fun `runtime probe can report authentication rejection without exposing credential`() = runTest {
        val fixture = fixture(secret = "never-in-error")
        val request = (fixture.access.resolve("asset-canonical") as OpenSubsonicMediaAccessResult.Ready).request
        var capturedParameters: Map<String, String> = emptyMap()
        val transport = OpenSubsonicRuntimeProbeTransport { _, parameters ->
            capturedParameters = parameters
            OpenSubsonicRuntimeProbeResult.AUTHENTICATION_REJECTED
        }

        val result = request.probe(transport)

        assertEquals(OpenSubsonicRuntimeProbeResult.AUTHENTICATION_REJECTED, result)
        assertEquals("never-in-error", capturedParameters["apiKey"])
        assertFalse(result.toString().contains("never-in-error"))
        assertFalse(request.toString().contains("never-in-error"))
    }

    private fun fixture(
        authMode: OpenSubsonicAuthMode = OpenSubsonicAuthMode.API_KEY,
        username: String = "",
        secret: String = "fixture-api-key",
        salt: String = "abcdef123456",
    ): Fixture {
        val persistence = FakeAccountPersistence()
        val credentials = FakeCredentialStore()
        val repository = OpenSubsonicAccountRepository(
            persistence = persistence,
            credentials = credentials,
            idFactory = { "home" },
        )
        repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://music.example.test",
                authMode = authMode,
                username = username,
                secretReplacement = secret,
            ),
        )
        credentials.readCalls = 0
        val asset = MediaAssetEntity(
            id = "asset-canonical",
            recordingId = "recording-canonical",
            provider = "opensubsonic:home",
            sourceKey = "server-track-native",
            uri = "opensubsonic://asset/home/server-track-native",
            path = null,
            dateAddedSeconds = 1L,
            dateModifiedSeconds = null,
            artworkUri = null,
        )
        val store = FakeMediaAssetStore(
            linkedMapOf(asset.id to asset),
        )
        return Fixture(
            persistence = persistence,
            credentials = credentials,
            repository = repository,
            asset = asset,
            store = store,
            access = OpenSubsonicMediaAccess(
                assets = store,
                accounts = repository,
                saltFactory = { salt },
            ),
        )
    }

    private data class Fixture(
        val persistence: FakeAccountPersistence,
        val credentials: FakeCredentialStore,
        val repository: OpenSubsonicAccountRepository,
        val asset: MediaAssetEntity,
        val store: FakeMediaAssetStore,
        val access: OpenSubsonicMediaAccess,
    )

    private class FakeMediaAssetStore(
        val assets: LinkedHashMap<String, MediaAssetEntity>,
    ) : OpenSubsonicMediaAssetStore {
        var writeCalls = 0

        override suspend fun mediaAsset(mediaAssetId: String): MediaAssetEntity? = assets[mediaAssetId]
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
        var readCalls = 0

        override fun read(accountId: String): String? {
            readCalls += 1
            return values[accountId]
        }

        override fun write(accountId: String, credential: String) {
            values[accountId] = credential
        }

        override fun clear(accountId: String) {
            values.remove(accountId)
        }
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
