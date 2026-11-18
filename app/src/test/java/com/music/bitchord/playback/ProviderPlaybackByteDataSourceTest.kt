package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import java.io.IOException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/** Byte delivery, not merely URL resolution, remains scoped to the producing extension. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class ProviderPlaybackByteDataSourceTest {
    private val addon = ProviderKey("addon", "music-one")
    private val module = ProviderKey("custom_module", "music-two")

    private fun policy(addonEnabled: Boolean, moduleEnabled: Boolean = true) =
        ProviderCapabilityPolicy(
            descriptors = listOf(addon, module).map {
                ProviderDescriptor(it, setOf(ProviderCapability.EXTENSION_PLAYBACK),
                    ProviderReadiness.READY)
            },
            choices = mapOf(ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(addon, addonEnabled, 0),
                ProviderChoice(module, moduleEnabled, 1),
            )),
        )

    private fun spec(
        lease: ProviderPlaybackByteLease?,
        uri: String = "https://cdn.example/audio.flac",
    ): DataSpec =
        DataSpec.Builder()
            .setUri(Uri.parse(uri))
            .setCustomData(lease)
            .build()

    private class Bytes : DataSource {
        var opens = 0
        var closes = 0
        var cursor = 0
        override fun open(dataSpec: DataSpec): Long {
            opens++
            cursor = 0
            return 3L
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (cursor == 3) return -1
            val count = minOf(length, 3 - cursor)
            repeat(count) { buffer[offset + it] = (cursor + it + 1).toByte() }
            cursor += count
            return count
        }

        override fun getUri(): Uri? = Uri.parse("https://cdn.example/audio.flac")
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun close() { closes++ }
    }

    @Test fun adaptiveSegmentInheritsRequiredHeadersOnlyOnManifestOrigin() {
        val manifestHost = MockWebServer()
        val externalCdn = MockWebServer()
        manifestHost.enqueue(MockResponse().setBody("same"))
        externalCdn.enqueue(MockResponse().setBody("other"))
        manifestHost.start()
        externalCdn.start()
        val gate = ProviderPolicyGate(policy(true))
        val session = ProviderPlaybackMediaSession()
        session.bind(
            ProviderPlaybackByteLease(gate, requireNotNull(
                gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))),
            manifestHost.url("/manifest.m3u8").toString(),
            mapOf("X-Source-Token" to "not-for-other-hosts",
                "User-Agent" to "ModulePlayer/1"),
        )
        val factory = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 65536L,
            fallbackLease = session::currentLease,
            requireLease = true,
        )
        try {
            factory.createDataSource().useSource { source ->
                val spec = session.withSegmentHeaders(
                    spec(null, manifestHost.url("/segment.ts").toString()))
                assertEquals(4L, source.open(spec))
                assertEquals(4, source.read(ByteArray(4), 0, 4))
            }
            factory.createDataSource().useSource { source ->
                val spec = session.withSegmentHeaders(
                    spec(null, externalCdn.url("/other.ts").toString()))
                assertEquals(5L, source.open(spec))
                assertEquals(5, source.read(ByteArray(5), 0, 5))
            }
            val sameOrigin = requireNotNull(manifestHost.takeRequest(5, TimeUnit.SECONDS))
            val differentOrigin = requireNotNull(externalCdn.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("not-for-other-hosts", sameOrigin.getHeader("X-Source-Token"))
            assertEquals("ModulePlayer/1", sameOrigin.getHeader("User-Agent"))
            assertTrue("Credential escaped manifest origin",
                differentOrigin.getHeader("X-Source-Token") == null)
            assertTrue(differentOrigin.getHeader("User-Agent") != "ModulePlayer/1")
        } finally {
            manifestHost.shutdown()
            externalCdn.shutdown()
        }
    }

    @Test fun explicitSegmentHeadersOverrideInheritedHeaderWithoutDroppingOtherKeys() {
        val gate = ProviderPolicyGate(policy(true))
        val session = ProviderPlaybackMediaSession()
        session.bind(
            ProviderPlaybackByteLease(gate, requireNotNull(
                gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))),
            "https://assets.example/master.m3u8",
            mapOf("X-Source-Token" to "manifest-token", "User-Agent" to "source"),
        )
        val spec = DataSpec.Builder().setUri("https://assets.example/seg.m4s")
            .setHttpRequestHeaders(mapOf("X-Source-Token" to "segment-token"))
            .build()
        val resolved = session.withSegmentHeaders(spec)
        assertEquals("segment-token", resolved.httpRequestHeaders["X-Source-Token"])
        assertEquals("source", resolved.httpRequestHeaders["User-Agent"])
        assertTrue(session.withSegmentHeaders(
            DataSpec.Builder().setUri("https://assets.example:8443/seg.m4s").build()
        ).httpRequestHeaders.isEmpty())

        val lowerCaseOverride = session.withSegmentHeaders(
            DataSpec.Builder().setUri("https://assets.example/seg2.m4s")
                .setHttpRequestHeaders(mapOf("x-source-token" to "new-token"))
                .build())
        assertEquals("new-token", lowerCaseOverride.httpRequestHeaders["x-source-token"])
        assertTrue("HTTP field names must not be duplicated with different casing",
            lowerCaseOverride.httpRequestHeaders.keys.none { it == "X-Source-Token" })
    }

    @Test fun cachedManifestAfterAccountChangeCannotInheritPriorSourceSecret() {
        val gate = ProviderPolicyGate(policy(true))
        val session = ProviderPlaybackMediaSession()
        val oldTicket = requireNotNull(
            gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        session.bind(
            ProviderPlaybackByteLease(gate, oldTicket),
            "https://assets.example/master.m3u8",
            mapOf("Authorization" to "Bearer previous-account"),
        )
        gate.replace(policy(false))
        gate.replace(policy(true))
        val reauthorized = requireNotNull(
            gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        // A complete cached manifest knows only its provider provenance.
        // Its new ticket does NOT prove that the old URL or private headers
        // still belong to the reauthorized account.
        session.bind(ProviderPlaybackByteLease(gate, reauthorized))
        val segment = session.withSegmentHeaders(
            DataSpec.Builder().setUri("https://assets.example/segment.ts").build())
        assertTrue(segment.httpRequestHeaders.isEmpty())
    }

    @Test fun revokedManifestTicketNeverSuppliesHeadersToLaterSegment() {
        val gate = ProviderPolicyGate(policy(true))
        val session = ProviderPlaybackMediaSession()
        session.bind(
            ProviderPlaybackByteLease(gate, requireNotNull(
                gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))),
            "https://assets.example/master.m3u8",
            mapOf("Authorization" to "Bearer secret"),
        )
        gate.replace(policy(false))
        try {
            session.withSegmentHeaders(
                DataSpec.Builder().setUri("https://assets.example/later.m4s").build())
            fail("Stale provider would expose headers")
        } catch (_: IOException) {}
    }

    @Test fun segmentedStreamUsesSameSourceLeaseWithoutCustomDataOnEachSegment() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("manifest"))
        server.enqueue(MockResponse().setBody("segment!"))
        server.start()
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        val session = ProviderPlaybackMediaSession()
        val lease = ProviderPlaybackByteLease(gate, ticket)
        session.bind(lease)
        val factory = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 65536L,
            fallbackLease = session::currentLease,
            requireLease = true,
        )
        try {
            factory.createDataSource().useSource { source ->
                assertEquals(8L, source.open(spec(lease, server.url("/playlist").toString())))
                assertEquals(8, source.read(ByteArray(8), 0, 8))
            }
            factory.createDataSource().useSource { segment ->
                // Media3's HLS/DASH segment DataSpec is a fresh HTTP URI with
                // no original customData. It must inherit the *media-item*
                // provider session, not become a generic unguarded request.
                assertEquals(8L, segment.open(spec(null, server.url("/segment").toString())))
                assertEquals(8, segment.read(ByteArray(8), 0, 8))
            }
        } finally { server.shutdown() }
        assertEquals("/playlist", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals("/segment", server.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    @Test fun revocationPhysicallyCancelsInflightAdaptiveSegment() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val gate = ProviderPolicyGate(policy(true))
        val session = ProviderPlaybackMediaSession()
        session.bind(ProviderPlaybackByteLease(gate, requireNotNull(
            gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))))
        val source = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(), 65536L,
            fallbackLease = session::currentLease,
            requireLease = true,
        ).createDataSource()
        val result = AtomicReference<Throwable?>()
        val done = AtomicBoolean(false)
        val worker = Thread {
            try { source.open(spec(null, server.url("/segment-waits").toString())) }
            catch (failure: Throwable) { result.set(failure) }
            finally {
                runCatching { source.close() }
                done.set(true)
            }
        }
        try {
            worker.start()
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            gate.replace(policy(false))
            worker.join(5_000)
            assertFalse("Revoked HLS/DASH segment still reading", worker.isAlive)
            assertTrue(done.get())
            assertTrue(result.get() is IOException)
        } finally {
            gate.replace(policy(false))
            worker.interrupt()
            server.shutdown()
        }
    }

    @Test fun siblingModuleSegmentsRemainUsableWhenAddonIsRevoked() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("segment"))
        server.start()
        val gate = ProviderPolicyGate(policy(true, true))
        val siblingSession = ProviderPlaybackMediaSession()
        siblingSession.bind(ProviderPlaybackByteLease(gate, requireNotNull(
            gate.open(ProviderCapability.EXTENSION_PLAYBACK, module))))
        val source = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 65536L,
            fallbackLease = siblingSession::currentLease,
            requireLease = true,
        ).createDataSource()
        try {
            gate.replace(policy(false, true))
            assertEquals(7L, source.open(spec(null, server.url("/module").toString())))
            assertEquals(7, source.read(ByteArray(7), 0, 7))
        } finally {
            runCatching { source.close() }
            server.shutdown()
        }
    }

    @Test fun revokedSessionRejectsNewAdaptiveSegmentBeforeNetworkOpen() {
        val server = MockWebServer()
        server.start()
        val gate = ProviderPolicyGate(policy(true))
        val session = ProviderPlaybackMediaSession()
        session.bind(ProviderPlaybackByteLease(gate, requireNotNull(
            gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))))
        gate.replace(policy(false))
        val segment = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 65536L,
            fallbackLease = session::currentLease,
            requireLease = true,
        ).createDataSource()
        try {
            try {
                segment.open(spec(null, server.url("/late-segment").toString()))
                fail("Revoked manifest permission leaked into an adaptive segment")
            } catch (_: IOException) {}
            assertTrue(server.takeRequest(400, TimeUnit.MILLISECONDS) == null)
        } finally {
            runCatching { segment.close() }
            server.shutdown()
        }
    }

    @Test fun adaptiveSegmentWithoutEstablishedSourceSessionFailsClosed() {
        val server = MockWebServer()
        server.start()
        val segment = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 65536L,
            fallbackLease = ProviderPlaybackMediaSession()::currentLease,
            requireLease = true,
        ).createDataSource()
        try {
            try {
                segment.open(spec(null, server.url("/unclaimed").toString()))
                fail("A segment without a resolved provider must not start HTTP")
            } catch (_: IOException) {}
            assertTrue(server.takeRequest(400, TimeUnit.MILLISECONDS) == null)
        } finally {
            runCatching { segment.close() }
            server.shutdown()
        }
    }

    @Test fun anotherSourceCannotTakeOverMediaItemSessionMidStream() {
        val gate = ProviderPolicyGate(policy(true, true))
        val session = ProviderPlaybackMediaSession()
        session.bind(ProviderPlaybackByteLease(gate, requireNotNull(
            gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))))
        try {
            session.bind(ProviderPlaybackByteLease(gate, requireNotNull(
                gate.open(ProviderCapability.EXTENSION_PLAYBACK, module))))
            fail("Provider identity cannot switch during one adaptive rendition")
        } catch (_: IOException) {}
    }

    private inline fun DataSource.useSource(action: (DataSource) -> Unit) {
        try { action(this) } finally { close() }
    }

    @Test fun revocationCancelsPhysicalOkHttpCallBeforeServerResponds() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        val calls = ProviderPlaybackCallFactory(
            OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(),
        )
        val source = ProviderPlaybackByteDataSource(
            OkHttpDataSource.Factory(calls).createDataSource(),
            cancelNetwork = calls::revoke,
        )
        val finished = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                source.open(spec(ProviderPlaybackByteLease(gate, ticket),
                    server.url("/never-respond").toString()))
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                runCatching { source.close() }
                finished.set(true)
            }
        }
        try {
            worker.start()
            assertTrue("HTTP request must reach the local fixture",
                server.takeRequest(5, TimeUnit.SECONDS) != null)
            gate.replace(policy(false))
            worker.join(5_000L)
            assertFalse("Revocation must cancel the pending HTTP Call, not just future reads",
                worker.isAlive)
            assertTrue(finished.get())
            assertTrue("Cancelled open must fail", error.get() is IOException)
        } finally {
            gate.replace(policy(false))
            worker.interrupt()
            server.shutdown()
        }
    }

    @Test fun protectedExtensionRedirectCannotSendHeadersToDifferentOrigin() {
        val origin = MockWebServer()
        val other = MockWebServer()
        other.start()
        origin.enqueue(MockResponse().setResponseCode(302)
            .addHeader("Location", other.url("/private").toString()))
        origin.start()
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        val source = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 64 * 1024L,
        ).createDataSource()
        try {
            val request = spec(ProviderPlaybackByteLease(gate, ticket),
                origin.url("/playlist").toString()).buildUpon()
                .setHttpRequestHeaders(mapOf("X-Source-Token" to "sensitive-header"))
                .build()
            try {
                source.open(request)
                fail("Provider-owned HTTP must not follow cross-origin redirect")
            } catch (_: IOException) {}
            assertEquals("/playlist", origin.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertTrue("Cross-origin server must see no request or secret headers",
                other.takeRequest(800, TimeUnit.MILLISECONDS) == null)
        } finally {
            runCatching { source.close() }
            origin.shutdown()
            other.shutdown()
        }
    }

    @Test fun protectedSameOriginRedirectKeepsWorking() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(302)
            .addHeader("Location", server.url("/audio").toString()))
        server.enqueue(MockResponse().setBody("audio"))
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        val source = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 64 * 1024L,
        ).createDataSource()
        try {
            assertEquals(5L, source.open(spec(
                ProviderPlaybackByteLease(gate, ticket), server.url("/entry").toString(),
            )))
            assertEquals(5, source.read(ByteArray(5), 0, 5))
            assertEquals("/entry", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("/audio", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        } finally {
            runCatching { source.close() }
            server.shutdown()
        }
    }

    @Test fun ordinaryNonProviderHttpKeepsCrossOriginRedirects() {
        val origin = MockWebServer()
        val cdn = MockWebServer()
        cdn.start()
        cdn.enqueue(MockResponse().setBody("audio"))
        origin.enqueue(MockResponse().setResponseCode(302)
            .addHeader("Location", cdn.url("/audio").toString()))
        origin.start()
        val source = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 64 * 1024L,
        ).createDataSource()
        try {
            assertEquals(5L, source.open(spec(null, origin.url("/entry").toString())))
            assertEquals(5, source.read(ByteArray(5), 0, 5))
            assertEquals("/audio", cdn.takeRequest(5, TimeUnit.SECONDS)?.path)
        } finally {
            runCatching { source.close() }
            origin.shutdown()
            cdn.shutdown()
        }
    }

    @Test fun sameDataSourceCanOpenAgainAfterCleanCloseWithFreshAuthorizedLease() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setBody("again"))
        server.start()
        val gate = ProviderPolicyGate(policy(true))
        val source = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 64 * 1024L,
        ).createDataSource()
        try {
            repeat(2) { index ->
                val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
                val path = if (index == 0) "/first" else "/again"
                assertEquals(5L, source.open(spec(
                    ProviderPlaybackByteLease(gate, ticket), server.url(path).toString(),
                )))
                val bytes = ByteArray(5)
                assertEquals(5, source.read(bytes, 0, bytes.size))
                assertEquals(if (index == 0) "first" else "again", String(bytes))
                source.close()
            }
            assertEquals("/first", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("/again", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        } finally {
            runCatching { source.close() }
            server.shutdown()
        }
    }

    @Test fun sameDataSourceCanRecoverAfterRevocationWithNewUserAuthorization() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("new"))
        server.start()
        val gate = ProviderPolicyGate(policy(true))
        val source = ProviderPlaybackByteDataSource.Factory(
            OkHttpClient.Builder().build(), 64 * 1024L,
        ).createDataSource()
        try {
            val firstTicket = requireNotNull(
                gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
            // With the ticket revoked before open, the next attempt must not
            // touch the network, but the reused DataSource is not poisoned.
            gate.replace(policy(false))
            try {
                source.open(spec(ProviderPlaybackByteLease(gate, firstTicket),
                    server.url("/denied").toString()))
                fail("Stale provider ticket must be rejected")
            } catch (_: IOException) {}
            source.close()
            gate.replace(policy(true))
            val newTicket = requireNotNull(
                gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
            assertEquals(3L, source.open(spec(
                ProviderPlaybackByteLease(gate, newTicket), server.url("/new").toString(),
            )))
            assertEquals(3, source.read(ByteArray(3), 0, 3))
            assertEquals("/new", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        } finally {
            runCatching { source.close() }
            server.shutdown()
        }
    }

    @Test fun disablingWinningAddonClosesTransportAndPreventsFurtherBytes() {
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        val backend = Bytes()
        val dataSource = ProviderPlaybackByteDataSource(backend)
        dataSource.open(spec(ProviderPlaybackByteLease(gate, ticket)))
        val bytes = ByteArray(3)
        assertEquals(1, dataSource.read(bytes, 0, 1))
        gate.replace(policy(false))
        assertTrue("Underlying HTTP must close on capability revocation", backend.closes > 0)
        try {
            dataSource.read(bytes, 1, 2)
            fail("Revoked provider published more playback bytes")
        } catch (_: IOException) {
            // Expected: stale source bytes are not returned to Media3.
        } finally { dataSource.close() }
    }

    @Test fun revocationBeforeOpenCannotSendAnHttpRequest() {
        val gate = ProviderPolicyGate(policy(true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        gate.replace(policy(false))
        val backend = Bytes()
        val dataSource = ProviderPlaybackByteDataSource(backend)
        try {
            dataSource.open(spec(ProviderPlaybackByteLease(gate, ticket)))
            fail("Stale ticket started a media request")
        } catch (_: IOException) {
            assertEquals(0, backend.opens)
        } finally { dataSource.close() }
    }

    @Test fun unrelatedModuleRevocationKeepsAuthorizedAddonBytesReadable() {
        val gate = ProviderPolicyGate(policy(true, true))
        val ticket = requireNotNull(gate.open(ProviderCapability.EXTENSION_PLAYBACK, addon))
        val backend = Bytes()
        val dataSource = ProviderPlaybackByteDataSource(backend)
        try {
            dataSource.open(spec(ProviderPlaybackByteLease(gate, ticket)))
            gate.replace(policy(true, false))
            assertEquals(3, dataSource.read(ByteArray(3), 0, 3))
            assertEquals(0, backend.closes)
        } finally { dataSource.close() }
    }

    @Test fun unrelatedHttpWithoutLeaseKeepsExistingBehavior() {
        val backend = Bytes()
        val dataSource = ProviderPlaybackByteDataSource(backend)
        try {
            dataSource.open(spec(null))
            assertEquals(3, dataSource.read(ByteArray(3), 0, 3))
            assertFalse(backend.closes > 0)
        } finally { dataSource.close() }
    }
}
