package com.music.bitchord

import android.content.Context
import com.music.bitchord.data.canvas.AppleArtistArt
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.canvas.VisualSurface
import com.music.bitchord.data.canvas.visualMediaCacheKey
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import com.music.bitchord.data.canvas.awaitAppleArtistPage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class AppleArtistArtCapabilityTest {
    private val key = BuiltInProviderKeys.APPLE_ARTIST_ART

    private fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
        descriptors = listOf(ProviderDescriptor(
            key, setOf(ProviderCapability.ARTIST_ARTWORK), ProviderReadiness.READY,
        )),
        choices = mapOf(ProviderCapability.ARTIST_ARTWORK to listOf(
            ProviderChoice(key, enabled, 0),
        )),
    )

    @After fun restore() {
        AppleArtistArtRepository.setPolicyGateForTests(null)
        AppleArtistArtRepository.clearOfflineMetadata()
    }

    @Test fun revokedArtistArtHidesPersistedHeroAndDoesNotDeleteOfflineRecord() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(
            "visual_artist_metadata_v1", Context.MODE_PRIVATE,
        )
        prefs.edit().clear().commit()
        AppleArtistArtRepository.init(context)
        val name = "Test Offline Apple Artist 20261116"
        val art = AppleArtistArt(
            heroUrl = "https://is1-ssl.mzstatic.com/image/thumb/artist/hero.jpg",
            logoUrl = null,
            logoAspect = 1.0f,
            videoUrl = null,
            background = 0xff112233.toInt(),
            accent = 0xff334455.toInt(),
            keyColor = null,
        )
        prefs.edit().putString(
            visualMediaCacheKey(VisualSurface.ARTIST, "apple-artist|testofflineappleartist20261116"),
            Json.encodeToString(AppleArtistArt.serializer(), art),
        ).commit()
        val gate = ProviderPolicyGate(policy(true))
        AppleArtistArtRepository.setPolicyGateForTests { gate }
        assertNotNull(AppleArtistArtRepository.cached(name))
        assertEquals(art.heroUrl, AppleArtistArtRepository.artFor(name)?.heroUrl)
        gate.replace(policy(false))
        assertNull(AppleArtistArtRepository.cached(name))
        assertNull(AppleArtistArtRepository.artFor(name))
        assertNotNull(prefs.getString(
            visualMediaCacheKey(VisualSurface.ARTIST, "apple-artist|testofflineappleartist20261116"),
            null,
        ))
        gate.replace(policy(true))
        assertEquals(art.heroUrl, AppleArtistArtRepository.cached(name)?.heroUrl)
    }

    @Test fun cancelledArtistPageRequestPhysicallyCancelsOkHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeadersDelay(30, TimeUnit.SECONDS)
                .setBody("late portrait"))
            server.start()
            val call = OkHttpClient().newCall(
                Request.Builder().url(server.url("/artist")).build())
            val gate = ProviderPolicyGate(policy(true))
            val pending = async(Dispatchers.IO) {
                com.music.bitchord.data.providers.ArtworkProbePolicyGuard { gate }.probe(
                    ProviderCapability.ARTIST_ARTWORK, key,
                ) { awaitAppleArtistPage(call) }
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            gate.replace(policy(false))
            assertNull(withTimeout(5_000) { pending.await() })
            assertEquals(true, call.isCanceled())
        }
    }

    @Test fun disabledArtistArtCannotExposePreviouslyPersistedExactStorefrontPortrait() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        AppleArtistArtRepository.init(context)
        val browseId = "itunes:br:artist:945612341"
        val url = "https://music.apple.com/br/artist/test-artist/945612341"
        val prefs = context.getSharedPreferences(
            "visual_artist_metadata_v1", Context.MODE_PRIVATE,
        )
        prefs.edit().putString(
            visualMediaCacheKey(VisualSurface.ARTIST, "apple-artist|exact|$browseId"),
            Json.encodeToString(AppleArtistArt.serializer(), AppleArtistArt(
                "https://is1-ssl.mzstatic.com/image/thumb/artist/portrait.jpg",
                null, 1.0f, null, 0, 0, null,
            )),
        ).commit()
        val gate = ProviderPolicyGate(policy(false))
        AppleArtistArtRepository.setPolicyGateForTests { gate }
        assertNull(AppleArtistArtRepository.artForExactStoreArtist(browseId, url))
        gate.replace(policy(true))
        assertNotNull(AppleArtistArtRepository.artForExactStoreArtist(browseId, url))
    }
}
