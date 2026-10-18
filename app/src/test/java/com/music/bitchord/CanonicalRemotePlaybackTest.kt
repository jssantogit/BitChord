package com.music.bitchord

import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.CanonicalRemotePlaybackUri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `playback never materializes or handles OpenSubsonic runtime request`() {
        val root = File(
            System.getProperty("user.dir"),
            "src/main/java/com/music/bitchord/playback",
        )
        val source = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        listOf(
            "OpenSubsonicRuntimeAudioRequest",
            "materializeDataSpec",
            "materializeAuthenticatedDataSpec",
        ).forEach { forbidden ->
            assertFalse("Playback crossed provider runtime transport boundary: $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun `provider-owned transport is resolved only from DataSource open`() {
        val providerSource = File(
            System.getProperty("user.dir"),
            "src/main/java/com/music/bitchord/data/catalog/provider/opensubsonic/OpenSubsonicMedia3Access.kt",
        ).readText()

        val openStart = providerSource.indexOf("override fun open(dataSpec: DataSpec)")
        val resolution = providerSource.indexOf("transport.resolveRuntime(")
        assertTrue("provider DataSource open must exist", openStart >= 0)
        assertTrue("runtime auth must be resolved from open-time", resolution > openStart)
    }
}
