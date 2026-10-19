package com.music.bitchord.observability

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeAudioRequest
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeTransport
import com.music.bitchord.diagnostics.BoundedPerformanceProbe
import com.music.bitchord.diagnostics.MainThreadStallState
import com.music.bitchord.playback.PlaybackE2ETraceObserver
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBaselineScenariosTest {
    @Test
    fun `existing OpenSubsonic transport measures twenty cold and warm requests with real HTTP cache`() = runBlocking {
        val serverHits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { exchange ->
            serverHits.incrementAndGet()
            val bytes = "fixture-audio".toByteArray()
            exchange.responseHeaders.add("Cache-Control", "public, max-age=3600")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val cacheDirectory = Files.createTempDirectory("bitchord-o1-cache").toFile()
        val cache = Cache(cacheDirectory, 1_048_576L)
        try {
            val client = OkHttpClient.Builder().cache(cache).build()
            val endpoint = "http://127.0.0.1:${server.address.port}/audio?private=do-not-export"
            val runtime = request(endpoint)
            val points = mutableListOf<StructuredTracePoint>()
            val sequence = AtomicInteger()
            val tracer = StructuredTracer(
                sink = StructuredTraceSink { points += it },
                traceIdFactory = { "private-${sequence.incrementAndGet()}" },
                referenceEncoder = SessionTraceReferenceEncoder("session-is-private"),
            )
            val transport = OpenSubsonicRuntimeTransport(
                performanceProbe = BoundedPerformanceProbe(sink = {}),
                tracer = tracer,
            ) {
                client.newCall(Request.Builder().url(endpoint).build()).execute().use { response ->
                    check(response.isSuccessful)
                    check(response.body?.string() == "fixture-audio")
                }
                OpenSubsonicMediaAccessResult.Ready(runtime)
            }
            val coldPoints = mutableListOf<StructuredTracePoint>()
            val warmPoints = mutableListOf<StructuredTracePoint>()
            repeat(20) {
                cache.evictAll()
                val coldStart = points.size
                assertSame(runtime, transport.resolveRuntime("recording-1", "asset-1", "opensubsonic:home"))
                coldPoints += points.drop(coldStart)
                val warmStart = points.size
                assertSame(runtime, transport.resolveRuntime("recording-1", "asset-1", "opensubsonic:home"))
                warmPoints += points.drop(warmStart)
            }
            assertEquals(20, serverHits.get()) // warm uses real OkHttp disk cache
            val cold = RuntimePerformanceBaseline.summarize(config(RuntimeBaselineScenario.PROVIDER_COLD), coldPoints)
            val warm = RuntimePerformanceBaseline.summarize(config(RuntimeBaselineScenario.PROVIDER_WARM), warmPoints)
            assertEquals(20, cold.correlatedTraces)
            assertEquals(20, warm.correlatedTraces)
            assertTrue(cold.phases.single { it.stage == "access_lookup" }.distribution.minNanos > 0L)
            assertTrue(warm.phases.single { it.stage == "access_lookup" }.distribution.minNanos > 0L)
            assertFalse(cold.toSafeText().contains("do-not-export"))
            assertFalse(warm.toSafeText().contains("127.0.0.1"))
            println("O1_PROVIDER_COLD_JVM\n${cold.toSafeText()}")
            println("O1_PROVIDER_WARM_JVM\n${warm.toSafeText()}")
        } finally {
            cache.close()
            server.stop(0)
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `provider failure reconnect and controlled coroutine saturation preserve correlated outcomes`() = runBlocking {
        val firstFailure = AtomicBoolean(true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/flaky") { exchange ->
            val failure = firstFailure.compareAndSet(true, false)
            val bytes = if (failure) "temporary" else "recovered"
            exchange.sendResponseHeaders(if (failure) 503 else 200, bytes.length.toLong())
            exchange.responseBody.use { it.write(bytes.toByteArray()) }
        }
        server.start()
        try {
            val client = OkHttpClient()
            val endpoint = "http://127.0.0.1:${server.address.port}/flaky?token=hidden"
            val request = request(endpoint)
            val points = Collections.synchronizedList(mutableListOf<StructuredTracePoint>())
            val sequence = AtomicInteger()
            val tracer = StructuredTracer(
                sink = StructuredTraceSink { points.add(it) },
                traceIdFactory = { "private-${sequence.incrementAndGet()}" },
                referenceEncoder = SessionTraceReferenceEncoder("secret-session"),
            )
            val transport = OpenSubsonicRuntimeTransport(
                performanceProbe = BoundedPerformanceProbe(sink = {}),
                tracer = tracer,
            ) {
                client.newCall(Request.Builder().url(endpoint).build()).execute().use { response ->
                    check(response.isSuccessful)
                    check(response.body?.string() == "recovered")
                }
                OpenSubsonicMediaAccessResult.Ready(request)
            }
            val failed = runCatching {
                transport.resolveRuntime("recording-1", "asset-1", "opensubsonic:home")
            }
            assertTrue(failed.isFailure)
            val recovered = transport.resolveRuntime("recording-1", "asset-1", "opensubsonic:home")
            assertSame(request, recovered)
            val concurrent = (0 until 8).map {
                async(Dispatchers.IO) {
                    transport.resolveRuntime("recording-1", "asset-1", "opensubsonic:home")
                }
            }.awaitAll()
            assertTrue(concurrent.all { it === request })
            val report = RuntimePerformanceBaseline.summarize(
                config(RuntimeBaselineScenario.PROVIDER_FAILURE_RECONNECT).copy(repetitions = 10, concurrency = 8),
                points.toList(),
            )
            assertEquals(10, report.correlatedTraces)
            assertEquals(1, report.phases.single { it.stage == "total" && it.outcome == TraceOutcome.FAILED }.distribution.count)
            assertEquals(9, report.phases.single { it.stage == "total" && it.outcome == TraceOutcome.SUCCEEDED }.distribution.count)
            assertFalse(report.toSafeText().contains("hidden"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `playback E2E callbacks and recovered stalls are measured without deriving nonexistent event durations`() {
        val points = mutableListOf<StructuredTracePoint>()
        var now = 1_000L
        val seq = AtomicInteger()
        val tracer = StructuredTracer(
            sink = StructuredTraceSink { points += it },
            traceIdFactory = { "private-${seq.incrementAndGet()}" },
            monotonicNanos = { now },
            referenceEncoder = SessionTraceReferenceEncoder("session-only"),
        )
        repeat(20) { index ->
            val observer = PlaybackE2ETraceObserver(tracer)
            val item = "secret-item-$index"
            observer.onPlayAccepted(item)
            now += 10_000_000L
            observer.onReady(item)
            now += 20_000_000L
            observer.onAudible(item)
        }
        val playback = RuntimePerformanceBaseline.summarize(
            config(RuntimeBaselineScenario.PLAYBACK_COLD), points,
        )
        assertEquals(20, playback.correlatedTraces)
        assertEquals(1, playback.phases.size) // E2E total only: ready/audible events have no duration
        assertEquals(30_000_000L, playback.phases.single().distribution.p95Nanos)
        assertFalse(playback.toSafeText().contains("secret-item"))

        val state = MainThreadStallState(250_000_000L)
        val recovered = mutableListOf<Long>()
        repeat(20) { index ->
            state.sample(250_000_000L + index * 10_000_000L)
            state.sample(10_000_000L)?.let { recovered += it.durationNanos }
        }
        val stalls = RuntimePerformanceBaseline.summarize(
            config(RuntimeBaselineScenario.STALL_RECOVERY), emptyList(), recovered,
        )
        assertEquals(20, stalls.phases.single().distribution.count)
        assertEquals(430_000_000L, stalls.phases.single().distribution.p95Nanos)
    }

    private fun config(scenario: RuntimeBaselineScenario) = RuntimeBaselineConfig(
        scenario = scenario,
        repetitions = 20,
        warmupRuns = 0,
        concurrency = 1,
        traceMode = TraceMode.STANDARD,
    )

    private fun request(endpoint: String) = OpenSubsonicRuntimeAudioRequest(
        recordingId = "recording-1",
        mediaAssetId = "asset-1",
        providerKey = "opensubsonic:home",
        endpointUrl = endpoint,
        sourceKey = "server-track-secret",
        auth = OpenSubsonicAuth.ApiKey("do-not-export"),
        saltFactory = { "private" },
    )
}
