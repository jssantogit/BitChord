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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicMediaAccessSecurityTest {

    @Test
    fun `missing credential leaves canonical asset untouched and fails safely`() = runTest {
        val fixture = fixture("credential-that-will-be-cleared")
        fixture.credentials.clear("home")
        val before = fixture.assets.toMap()

        val result = fixture.access.resolve("asset-1")

        assertEquals(
            OpenSubsonicMediaAccessFailure.CREDENTIAL_UNAVAILABLE,
            (result as OpenSubsonicMediaAccessResult.Failure).reason,
        )
        assertEquals(before, fixture.assets)
        assertEquals(0, fixture.assetStore.writeCalls)
    }

    @Test
    fun `throwing transport cannot leak authenticated url or api key through runtime result`() = runTest {
        val secret = "never-leak-api-key"
        val fixture = fixture(secret)
        val request = (fixture.access.resolve("asset-1") as OpenSubsonicMediaAccessResult.Ready).request
        var transportSawSecret = false
        val transport = OpenSubsonicRuntimeProbeTransport { endpoint, parameters ->
            transportSawSecret = parameters["apiKey"] == secret
            error("request failed: $endpoint?apiKey=$secret")
        }

        val result = request.probe(transport)

        assertTrue(transportSawSecret)
        assertEquals(OpenSubsonicRuntimeProbeResult.NETWORK_UNAVAILABLE, result)
        assertFalse(result.toString().contains(secret))
        assertFalse(request.toString().contains(secret))
        assertFalse(request.endpointUrl.contains(secret))
    }

    private fun fixture(secret: String): Fixture {
        val persistence = FakeAccountPersistence()
        val credentials = FakeCredentialStore()
        val accounts = OpenSubsonicAccountRepository(
            persistence = persistence,
            credentials = credentials,
            idFactory = { "home" },
        )
        accounts.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://music.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = secret,
            ),
        )
        val asset = MediaAssetEntity(
            id = "asset-1",
            recordingId = "recording-1",
            provider = "opensubsonic:home",
            sourceKey = "server-track-1",
            uri = "opensubsonic://asset/home/server-track-1",
            path = null,
            dateAddedSeconds = 1L,
            dateModifiedSeconds = null,
            artworkUri = null,
        )
        val assets = linkedMapOf(asset.id to asset)
        val assetStore = FakeMediaAssetStore(assets)
        return Fixture(
            credentials = credentials,
            assets = assets,
            assetStore = assetStore,
            access = OpenSubsonicMediaAccess(assetStore, accounts) { "abcdef123456" },
        )
    }

    private data class Fixture(
        val credentials: FakeCredentialStore,
        val assets: LinkedHashMap<String, MediaAssetEntity>,
        val assetStore: FakeMediaAssetStore,
        val access: OpenSubsonicMediaAccess,
    )

    private class FakeMediaAssetStore(
        private val assets: Map<String, MediaAssetEntity>,
    ) : OpenSubsonicMediaAssetStore {
        var writeCalls: Int = 0

        override suspend fun mediaAsset(mediaAssetId: String): MediaAssetEntity? = assets[mediaAssetId]
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
}
