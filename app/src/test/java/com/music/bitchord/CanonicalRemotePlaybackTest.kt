package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessFailure
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessResult
import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.CanonicalRemotePlaybackResolver
import com.music.bitchord.playback.CanonicalRemotePlaybackUri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalRemotePlaybackTest {

    @Test
    fun `logical uri contains canonical request identity only`() {
        val request = CanonicalRemotePlaybackRequest(
            recordingId = "recording-1",
            mediaAssetId = "asset-1",
            providerKey = "opensubsonic:home",
        )

        val uri = CanonicalRemotePlaybackUri.encode(request)

        assertEquals(request, CanonicalRemotePlaybackUri.decode(uri))
        assertTrue(uri.startsWith("bitchord://canonical?"))
        assertFalse(uri.contains("server-track-native"))
        assertFalse(uri.contains("sourceKey", ignoreCase = true))
        assertFalse(uri.contains("apiKey", ignoreCase = true))
        assertFalse(uri.contains("password", ignoreCase = true))
        assertFalse(uri.contains("token", ignoreCase = true))
    }

    @Test
    fun `provider seam is invoked lazily only when resolver opens item`() = runTest {
        var calls = 0
        var requestedAsset: String? = null
        val resolver = CanonicalRemotePlaybackResolver(
            openSubsonicResolve = { mediaAssetId ->
                calls += 1
                requestedAsset = mediaAssetId
                OpenSubsonicMediaAccessResult.Failure(
                    OpenSubsonicMediaAccessFailure.MEDIA_ASSET_NOT_FOUND,
                )
            },
        )
        val logicalUri = CanonicalRemotePlaybackUri.encode(
            CanonicalRemotePlaybackRequest(
                recordingId = "recording-1",
                mediaAssetId = "asset-1",
                providerKey = "opensubsonic:home",
            ),
        )

        assertEquals(0, calls)
        val error = assertThrows(IOException::class.java) {
            runBlocking { resolver.resolve(logicalUri) }
        }

        assertEquals(1, calls)
        assertEquals("asset-1", requestedAsset)
        assertFalse(error.message.orEmpty().contains("server-track-native"))
    }

    @Test
    fun `account mismatch and forgotten account stay provider failures without locator leakage`() = runTest {
        listOf(
            OpenSubsonicMediaAccessFailure.ACCOUNT_MISMATCH,
            OpenSubsonicMediaAccessFailure.ACCOUNT_NOT_CONFIGURED,
        ).forEach { failure ->
            var calls = 0
            val resolver = CanonicalRemotePlaybackResolver(
                openSubsonicResolve = {
                    calls += 1
                    OpenSubsonicMediaAccessResult.Failure(failure)
                },
            )
            val logicalUri = CanonicalRemotePlaybackUri.encode(
                CanonicalRemotePlaybackRequest(
                    recordingId = "recording-1",
                    mediaAssetId = "asset-1",
                    providerKey = "opensubsonic:home",
                ),
            )

            val error = assertThrows(IOException::class.java) {
                runBlocking { resolver.resolve(logicalUri) }
            }

            assertEquals(1, calls)
            assertFalse(error.message.orEmpty().contains("server-track-native"))
            assertFalse(error.message.orEmpty().contains("password", ignoreCase = true))
            assertFalse(error.message.orEmpty().contains("apiKey", ignoreCase = true))
        }
    }

    @Test
    fun `unsupported provider fails before provider-owned OpenSubsonic access`() = runTest {
        var calls = 0
        val resolver = CanonicalRemotePlaybackResolver(
            openSubsonicResolve = {
                calls += 1
                OpenSubsonicMediaAccessResult.Failure(
                    OpenSubsonicMediaAccessFailure.MEDIA_ASSET_NOT_FOUND,
                )
            },
        )
        val logicalUri = CanonicalRemotePlaybackUri.encode(
            CanonicalRemotePlaybackRequest(
                recordingId = "recording-1",
                mediaAssetId = "asset-1",
                providerKey = "other:home",
            ),
        )

        assertThrows(IOException::class.java) {
            runBlocking { resolver.resolve(logicalUri) }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `playback package contains no second OpenSubsonic auth stack`() {
        val root = File(
            System.getProperty("user.dir"),
            "src/main/java/com/music/bitchord/playback",
        )
        val source = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        listOf(
            "OpenSubsonicAuth",
            "OpenSubsonicAccountConfig",
            "openSubsonicAuthenticationParameters",
            "MessageDigest",
            "saltFactory",
            "/rest/stream.view",
        ).forEach { forbidden ->
            assertFalse("Playback duplicated provider responsibility: $forbidden", source.contains(forbidden))
        }
    }
}
