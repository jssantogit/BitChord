package com.music.bitchord.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * Parse genuine media manifests with the same Media3 HLS/DASH readers that
 * playback uses, then load the referenced segment using a provider-scoped
 * network DataSource. This exercises manifest -> parsed URL -> segment bytes
 * and opt-out across multiple HTTP requests without external services.
 *
 * It is not an ExoPlayer renderer/device smoke or the full MediaSource period
 * lifecycle; those remain distinct acceptance criteria for PR #219.
 */
@OptIn(UnstableApi::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class ProviderAdaptiveManifestTransportIntegrationTest {
    private val key = ProviderKey("addon", "manifest-integration")

    private fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
        descriptors = listOf(ProviderDescriptor(
            key, setOf(ProviderCapability.EXTENSION_PLAYBACK),
            ProviderReadiness.READY,
        )),
        choices = mapOf(ProviderCapability.EXTENSION_PLAYBACK to
            listOf(ProviderChoice(key, enabled, 0))),
    )

    private fun spec(uri: String, lease: ProviderPlaybackByteLease? = null,
                     headers: Map<String, String> = emptyMap()): DataSpec =
        DataSpec.Builder().setUri(Uri.parse(uri))
            .setCustomData(lease).setHttpRequestHeaders(headers).build()

    private fun bytes(source: DataSource, dataSpec: DataSpec): ByteArray {
        try {
            source.open(dataSpec)
            val sink = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == -1) break
                if (count > 0) sink.write(buffer, 0, count)
            }
            return sink.toByteArray()
        } finally {
            source.close()
        }
    }

    @Test fun media3HlsParserLoadsRealSegmentsWithInheritedOriginHeadersAndOptOut() {
        val server = MockWebServer()
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:4.0,
            piece-1.ts?v=same
            #EXTINF:4.0,
            piece-2.ts?v=same
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"
        server.enqueue(MockResponse().setBody(playlist))
        server.enqueue(MockResponse().setBody("first-segment"))
        server.start()
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, key))
        val lease = ProviderPlaybackByteLease(gate, ticket)
        val session = ProviderPlaybackMediaSession()
        val manifestUrl = server.url("/album/master.m3u8").toString()
        session.bind(lease, manifestUrl, mapOf("X-Source-Token" to "one-session"))
        val factory = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 65536L,
            fallbackLease = session::currentLease, requireLease = true,
        )
        try {
            val xml = bytes(factory.createDataSource(),
                spec(manifestUrl, lease, mapOf("X-Source-Token" to "one-session")))
            val parsed = HlsPlaylistParser().parse(
                Uri.parse(manifestUrl), ByteArrayInputStream(xml),
            ) as HlsMediaPlaylist
            assertEquals(2, parsed.segments.size)
            val firstUrl = URI(manifestUrl)
                .resolve(parsed.segments[0].url).toString()
            val secondUrl = URI(manifestUrl)
                .resolve(parsed.segments[1].url).toString()
            assertEquals("first-segment", String(bytes(factory.createDataSource(),
                session.withSegmentHeaders(spec(firstUrl)))))
            assertEquals("one-session",
                server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("X-Source-Token"))
            assertEquals("one-session",
                server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("X-Source-Token"))

            gate.replace(policy(false))
            try {
                bytes(factory.createDataSource(),
                    session.withSegmentHeaders(spec(secondUrl)))
                fail("The second HLS segment must not run after opt-out")
            } catch (_: IOException) {}
            assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
        } finally {
            server.shutdown()
        }
    }

    @Test fun media3DashParserLoadsMpdSegmentAndRejectsLaterRequestAfterRevocation() {
        val server = MockWebServer()
        server.start()
        val baseUrl = server.url("/dash/").toString()
        val manifestUrl = server.url("/dash/master.mpd").toString()
        val mediaTemplate = "piece-" + '$' + "Number" + '$' + ".m4s?v=version"
        val mpd = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
                profiles="urn:mpeg:dash:profile:isoff-on-demand:2011"
                type="static" minBufferTime="PT1S"
                mediaPresentationDuration="PT8S">
              <Period id="0" start="PT0S">
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <Representation id="audio" bandwidth="128000" codecs="mp4a.40.2">
                    <BaseURL>$baseUrl</BaseURL>
                    <SegmentTemplate timescale="1" duration="4" startNumber="1"
                        initialization="init.mp4" media="$mediaTemplate"/>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(mpd))
        server.enqueue(MockResponse().setBody("dash-segment"))
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, key))
        val lease = ProviderPlaybackByteLease(gate, ticket)
        val session = ProviderPlaybackMediaSession()
        session.bind(lease, manifestUrl)
        val factory = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 65536L,
            fallbackLease = session::currentLease, requireLease = true,
        )
        try {
            val manifestBody = bytes(factory.createDataSource(), spec(manifestUrl, lease))
            val parsed = DashManifestParser().parse(
                Uri.parse(manifestUrl), ByteArrayInputStream(manifestBody))
            val representation = parsed.getPeriod(0).adaptationSets.first()
                .representations.first()
            val index = requireNotNull(representation.index)
            val firstUrl = index.getSegmentUrl(index.firstSegmentNum)
                .resolveUriString(representation.baseUrls.first().url)
            assertEquals("dash-segment", String(bytes(factory.createDataSource(),
                session.withSegmentHeaders(spec(firstUrl)))))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/dash/piece-1.m4s?v=version",
                server.takeRequest(5, TimeUnit.SECONDS)?.path)

            gate.replace(policy(false))
            val secondUrl = index.getSegmentUrl(index.firstSegmentNum + 1)
                .resolveUriString(representation.baseUrls.first().url)
            try {
                bytes(factory.createDataSource(),
                    session.withSegmentHeaders(spec(secondUrl)))
                fail("The second DASH segment must not run after opt-out")
            } catch (_: IOException) {}
            assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
        } finally {
            server.shutdown()
        }
    }
}
