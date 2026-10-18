package com.music.bitchord

import android.util.Base64
import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountDraft
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkAccess
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkAccessFailure
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkAccessResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkAssetStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkCache
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkFetchResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoadResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkTransport
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkTransportResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCredentialStore
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicArtworkAccessTest {

    @Test
    fun `known artwork resolves from canonical media asset through correct account without reading credential`() = runTest {
        val fixture = fixture()

        val result = fixture.access.resolve("asset-canonical")

        val ready = result as OpenSubsonicArtworkAccessResult.Ready
        assertEquals("recording-canonical", ready.request.recordingId)
        assertEquals("asset-canonical", ready.request.mediaAssetId)
        assertEquals("opensubsonic:home", ready.request.providerKey)
        assertTrue(ready.request.cacheKey.startsWith("opensubsonic-artwork:v1:asset-canonical:"))
        assertEquals(0, fixture.credentials.readCalls)
        assertFalse(ready.request.toString().contains("cover-native"))
    }

    @Test
    fun `api key is accessed only by runtime fetch and never enters cache key or persisted state`() = runTest {
        val fixture = fixture(secret = "runtime-api-key")
        val ready = fixture.access.resolve("asset-canonical") as OpenSubsonicArtworkAccessResult.Ready
        assertEquals(0, fixture.credentials.readCalls)
        assertFalse(ready.request.cacheKey.contains("runtime-api-key"))
        assertFalse(fixture.asset.toString().contains("runtime-api-key"))
        assertFalse(fixture.persistence.record.toString().contains("runtime-api-key"))
        var capturedEndpoint = ""
        var capturedParameters: Map<String, String> = emptyMap()
        val transport = OpenSubsonicArtworkTransport { endpoint, parameters ->
            capturedEndpoint = endpoint
            capturedParameters = parameters
            OpenSubsonicArtworkTransportResult.Success(
                bytes = byteArrayOf(1, 2, 3),
                contentType = "image/webp",
            )
        }

        val result = ready.request.fetch(transport)

        assertEquals(1, fixture.credentials.readCalls)
        assertEquals("https://music.example.test/rest/getCoverArt.view", capturedEndpoint)
        assertEquals("cover-native", capturedParameters["id"])
        assertEquals("runtime-api-key", capturedParameters["apiKey"])
        assertFalse(capturedEndpoint.contains("runtime-api-key"))
        assertFalse(ready.request.cacheKey.contains("runtime-api-key"))
        assertFalse(result.toString().contains("runtime-api-key"))
        assertFalse(ready.request.toString().contains("runtime-api-key"))
    }

    @Test
    fun `password token and salt exist only in runtime transport parameters`() = runTest {
        val fixture = fixture(
            authMode = OpenSubsonicAuthMode.PASSWORD,
            username = "listener",
            secret = "secret-password",
            salt = "abcdef123456",
        )
        val ready = fixture.access.resolve("asset-canonical") as OpenSubsonicArtworkAccessResult.Ready
        assertEquals(0, fixture.credentials.readCalls)
        var capturedParameters: Map<String, String> = emptyMap()
        val transport = OpenSubsonicArtworkTransport { _, parameters ->
            capturedParameters = parameters
            OpenSubsonicArtworkTransportResult.Success(byteArrayOf(9), "image/jpeg")
        }

        val result = ready.request.fetch(transport)

        val expectedToken = md5("secret-passwordabcdef123456")
        assertEquals("listener", capturedParameters["u"])
        assertEquals("abcdef123456", capturedParameters["s"])
        assertEquals(expectedToken, capturedParameters["t"])
        assertNull(capturedParameters["p"])
        assertFalse(ready.request.cacheKey.contains("secret-password"))
        assertFalse(ready.request.cacheKey.contains("abcdef123456"))
        assertFalse(ready.request.cacheKey.contains(expectedToken))
        assertFalse(fixture.asset.toString().contains("secret-password"))
        assertFalse(fixture.persistence.record.toString().contains("secret-password"))
        assertFalse(result.toString().contains("secret-password"))
        assertFalse(result.toString().contains(expectedToken))
    }

    @Test
    fun `provider and logical artwork account mismatch fail safely before credential access`() = runTest {
        val fixture = fixture()
        fixture.store.assets["wrong-provider"] = fixture.asset.copy(
            id = "wrong-provider",
            provider = "local-mediastore",
        )
        fixture.store.assets["wrong-account"] = fixture.asset.copy(
            id = "wrong-account",
            artworkUri = logicalArtworkUri("other", "cover-native"),
        )

        val provider = fixture.access.resolve("wrong-provider") as OpenSubsonicArtworkAccessResult.Failure
        val account = fixture.access.resolve("wrong-account") as OpenSubsonicArtworkAccessResult.Failure

        assertEquals(OpenSubsonicArtworkAccessFailure.UNSUPPORTED_PROVIDER, provider.reason)
        assertEquals(OpenSubsonicArtworkAccessFailure.ACCOUNT_MISMATCH, account.reason)
        assertEquals(0, fixture.credentials.readCalls)
    }

    @Test
    fun `forgotten account returns safe placeholder state and preserves media asset`() = runTest {
        val fixture = fixture()
        fixture.repository.forget()
        val before = fixture.store.assets.toMap()

        val result = fixture.access.resolve("asset-canonical")

        assertEquals(
            OpenSubsonicArtworkAccessFailure.ACCOUNT_NOT_CONFIGURED,
            (result as OpenSubsonicArtworkAccessResult.Failure).reason,
        )
        assertEquals(before, fixture.store.assets)
        assertEquals(0, fixture.store.writeCalls)
    }

    @Test
    fun `missing or malformed artwork reference resolves to placeholder without credential access`() = runTest {
        val fixture = fixture()
        fixture.store.assets["missing-art"] = fixture.asset.copy(id = "missing-art", artworkUri = null)
        fixture.store.assets["bad-art"] = fixture.asset.copy(id = "bad-art", artworkUri = "https://example.test/cover.jpg")

        val missing = fixture.access.resolve("missing-art") as OpenSubsonicArtworkAccessResult.Failure
        val malformed = fixture.access.resolve("bad-art") as OpenSubsonicArtworkAccessResult.Failure

        assertEquals(OpenSubsonicArtworkAccessFailure.ARTWORK_NOT_AVAILABLE, missing.reason)
        assertEquals(OpenSubsonicArtworkAccessFailure.INVALID_ARTWORK_REFERENCE, malformed.reason)
        assertEquals(0, fixture.credentials.readCalls)
    }

    @Test
    fun `network failure is secret free and cannot mutate canonical catalog`() = runTest {
        val fixture = fixture(secret = "never-leak-key")
        val before = fixture.store.assets.toMap()
        val request = (fixture.access.resolve("asset-canonical") as OpenSubsonicArtworkAccessResult.Ready).request
        val transport = OpenSubsonicArtworkTransport { endpoint, parameters ->
            throw IllegalStateException("boom $endpoint?apiKey=${parameters["apiKey"]}")
        }

        val result = request.fetch(transport)

        assertEquals(OpenSubsonicArtworkFetchResult.NetworkUnavailable, result)
        assertFalse(result.toString().contains("never-leak-key"))
        assertEquals(before, fixture.store.assets)
        assertEquals(0, fixture.store.writeCalls)
    }

    @Test
    fun `cache key is stable credential free and changes with safe artwork revision`() = runTest {
        val first = fixture(secret = "first-secret")
        val second = fixture(secret = "different-secret")

        val firstKey = (first.access.resolve("asset-canonical") as OpenSubsonicArtworkAccessResult.Ready).request.cacheKey
        val secondKey = (second.access.resolve("asset-canonical") as OpenSubsonicArtworkAccessResult.Ready).request.cacheKey
        assertEquals(firstKey, secondKey)
        assertFalse(firstKey.contains("first-secret"))
        assertFalse(firstKey.contains("different-secret"))
        assertFalse(firstKey.contains("cover-native"))

        first.store.assets["asset-canonical"] = first.asset.copy(
            artworkUri = logicalArtworkUri("home", "cover-revision-2"),
        )
        val revised = (first.access.resolve("asset-canonical") as OpenSubsonicArtworkAccessResult.Ready).request.cacheKey
        assertFalse(firstKey == revised)
    }

    @Test
    fun `loader uses cached image when provider later becomes unavailable`() = runTest {
        val fixture = fixture()
        val cache = FakeArtworkCache()
        var networkCalls = 0
        val online = OpenSubsonicArtworkTransport { _, _ ->
            networkCalls += 1
            OpenSubsonicArtworkTransportResult.Success(byteArrayOf(4, 5, 6), "image/png")
        }
        val firstLoader = OpenSubsonicArtworkLoader(fixture.access, online, cache)

        val first = firstLoader.load("asset-canonical") as OpenSubsonicArtworkLoadResult.Ready
        assertArrayEquals(byteArrayOf(4, 5, 6), first.bytes)
        assertFalse(first.fromCache)
        assertEquals(1, networkCalls)

        val offline = OpenSubsonicArtworkTransport { _, _ ->
            networkCalls += 1
            OpenSubsonicArtworkTransportResult.NetworkUnavailable
        }
        val restartedLoader = OpenSubsonicArtworkLoader(fixture.access, offline, cache)
        val cached = restartedLoader.load("asset-canonical") as OpenSubsonicArtworkLoadResult.Ready

        assertArrayEquals(byteArrayOf(4, 5, 6), cached.bytes)
        assertTrue(cached.fromCache)
        assertEquals(1, networkCalls)
        assertEquals(0, fixture.store.writeCalls)
    }

    @Test
    fun `authentication rejection and missing credential remain typed and redacted`() = runTest {
        val fixture = fixture(secret = "sensitive-key")
        val request = (fixture.access.resolve("asset-canonical") as OpenSubsonicArtworkAccessResult.Ready).request
        val rejected = request.fetch(
            OpenSubsonicArtworkTransport { _, _ -> OpenSubsonicArtworkTransportResult.AuthenticationRejected },
        )
        assertEquals(OpenSubsonicArtworkFetchResult.AuthenticationRejected, rejected)
        assertFalse(rejected.toString().contains("sensitive-key"))

        fixture.credentials.clear("home")
        val missing = request.fetch(
            OpenSubsonicArtworkTransport { _, _ -> error("transport must not run without credential") },
        )
        assertEquals(OpenSubsonicArtworkFetchResult.CredentialUnavailable, missing)
    }

    @Test
    fun `artwork UI contract remains canonical and has no Song video id identity`() {
        val trackFields = CanonicalBrowseTrack::class.java.declaredFields.map { it.name }.toSet()
        val assetFields = CanonicalBrowseMediaAsset::class.java.declaredFields.map { it.name }.toSet()

        assertTrue("recordingId" in trackFields)
        assertTrue("mediaAssetId" in assetFields)
        assertFalse("videoId" in trackFields)
        assertFalse("videoId" in assetFields)
        assertFalse("sourceKey" in trackFields)
        assertFalse("sourceKey" in assetFields)
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
            artworkUri = logicalArtworkUri("home", "cover-native"),
        )
        val store = FakeArtworkAssetStore(linkedMapOf(asset.id to asset))
        return Fixture(
            persistence = persistence,
            credentials = credentials,
            repository = repository,
            asset = asset,
            store = store,
            access = OpenSubsonicArtworkAccess(
                assets = store,
                accounts = repository,
                saltFactory = { salt },
            ),
        )
    }

    private fun logicalArtworkUri(accountId: String, coverArtId: String): String =
        "opensubsonic://artwork/${encode(accountId)}/${encode(coverArtId)}"

    private fun encode(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private data class Fixture(
        val persistence: FakeAccountPersistence,
        val credentials: FakeCredentialStore,
        val repository: OpenSubsonicAccountRepository,
        val asset: MediaAssetEntity,
        val store: FakeArtworkAssetStore,
        val access: OpenSubsonicArtworkAccess,
    )

    private class FakeArtworkAssetStore(
        val assets: LinkedHashMap<String, MediaAssetEntity>,
    ) : OpenSubsonicArtworkAssetStore {
        var writeCalls = 0

        override suspend fun mediaAsset(mediaAssetId: String): MediaAssetEntity? = assets[mediaAssetId]
    }

    private class FakeArtworkCache : OpenSubsonicArtworkCache {
        private val values = mutableMapOf<String, ByteArray>()

        override suspend fun read(cacheKey: String): ByteArray? = values[cacheKey]?.copyOf()

        override suspend fun write(cacheKey: String, bytes: ByteArray) {
            values[cacheKey] = bytes.copyOf()
        }
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
