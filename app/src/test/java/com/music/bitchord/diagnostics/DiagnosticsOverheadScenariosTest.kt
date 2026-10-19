package com.music.bitchord.diagnostics

import com.music.bitchord.observability.SessionTraceReferenceEncoder
import com.music.bitchord.observability.StructuredTraceSink
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceMode
import com.music.bitchord.observability.TraceOutcome
import com.music.bitchord.observability.TraceVerbosity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsOverheadScenariosTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun realBoundedDiagnosticComponentsProduceRepeatedModeSpecificMeasurements() {
        val config = DiagnosticsOverheadConfig(
            repetitions = 20,
            warmups = 5,
            concurrentProducers = 1,
            captureBytesLimit = 512,
            writerQueueCapacity = 32,
        )
        val timings = mutableListOf<DiagnosticsOverheadObservation>()
        val counters = mutableListOf<DiagnosticsModeCounters>()
        val secrets = listOf(
            "private.invalid",
            "secret-track-id",
            "real-secret",
            "dd51b6f2-2a2a-4f7b-aa8f-a8aa0136a70e",
        )

        DiagnosticsCaptureMode.entries.forEach { mode ->
            val store = DiagnosticFileStore(
                rootDir = temporaryFolder.newFolder(mode.wireName),
                maxCaptureBytes = config.captureBytesLimit.toLong(),
            )
            val buffer = DiagnosticBuffer(capacity = 64)
            val writer = DiagnosticWriteQueue(queueCapacity = config.writerQueueCapacity)
            val accepted = AtomicInteger(0)
            var sample = false
            val sink = StructuredTraceSink { point ->
                val startedMap = System.nanoTime()
                val mapped = StructuredTraceDiagnosticMapper.map(point)
                if (sample) timings += DiagnosticsOverheadObservation(
                    mode, DiagnosticsOverheadPhase.STRUCTURED_MAP, System.nanoTime() - startedMap,
                )

                val event = DiagnosticEvent(
                    epochMillis = 1_700_000_000_000L,
                    monotonicNanos = System.nanoTime(),
                    sessionId = "session-fixed",
                    level = mapped.level,
                    category = mapped.category,
                    name = mapped.name,
                    message = DiagnosticSanitizer.freeText(
                        "upstream=https://private.invalid/stream?id=secret-track-id&token=real-secret",
                    ),
                    threadName = "test",
                    durationNanos = mapped.durationNanos,
                    metadata = DiagnosticSanitizer.metadata(
                        mapped.metadata + ("access_token" to "real-secret"),
                    ),
                    error = null,
                )
                val startedAppend = System.nanoTime()
                buffer.append(event)
                if (sample) timings += DiagnosticsOverheadObservation(
                    mode, DiagnosticsOverheadPhase.BUFFER_APPEND, System.nanoTime() - startedAppend,
                )

                // Production Diagnostics.record formats even when capture is disabled.
                val startedFormat = System.nanoTime()
                val line = DiagnosticFormatter.format(event)
                if (sample) timings += DiagnosticsOverheadObservation(
                    mode, DiagnosticsOverheadPhase.EVENT_FORMAT, System.nanoTime() - startedFormat,
                )
                if (mode != DiagnosticsCaptureMode.OFF) {
                    val startedOffer = System.nanoTime()
                    val enqueued = writer.offer { store.appendCapture(line) }
                    if (enqueued) accepted.incrementAndGet()
                    if (sample) timings += DiagnosticsOverheadObservation(
                        mode, DiagnosticsOverheadPhase.WRITER_OFFER, System.nanoTime() - startedOffer,
                    )
                }
            }
            val ids = AtomicInteger()
            val tracer = StructuredTracer(
                mode = if (mode == DiagnosticsCaptureMode.VERBOSE) TraceMode.VERBOSE else TraceMode.STANDARD,
                sink = sink,
                traceIdFactory = { "trace-private-" + ids.incrementAndGet() },
                referenceEncoder = SessionTraceReferenceEncoder("never-export-session-salt"),
            )
            repeat(config.warmups + config.repetitions) { round ->
                sample = round >= config.warmups
                val trace = tracer.startTrace(
                    TraceFeature.PLAYBACK, "play_to_audible",
                    attributes = mapOf("trackRef" to tracer.reference("secret-track-id")),
                )
                trace.event("command_accepted", TraceOutcome.SUCCEEDED)
                trace.event("candidate_detail", TraceOutcome.SUCCEEDED, verbosity = TraceVerbosity.VERBOSE)
                trace.complete(TraceOutcome.SUCCEEDED)
                if (sample && mode != DiagnosticsCaptureMode.OFF) {
                    val startedDrain = System.nanoTime()
                    assertTrue(writer.awaitWritesIssuedBeforeCall(timeoutMillis = 2_000L))
                    timings += DiagnosticsOverheadObservation(
                        mode, DiagnosticsOverheadPhase.WRITER_DRAIN, System.nanoTime() - startedDrain,
                    )
                    val startedExport = System.nanoTime()
                    store.createExport(
                        metadataLines = listOf("mode=" + mode.wireName, "droppedPersistenceWrites=" + writer.droppedWrites),
                        recentEventLines = buffer.snapshot().map(DiagnosticFormatter::format),
                        fileName = "o2-run-" + round + ".txt",
                    )
                    timings += DiagnosticsOverheadObservation(
                        mode, DiagnosticsOverheadPhase.EXPORT_CREATE, System.nanoTime() - startedExport,
                    )
                }
            }
            assertTrue(writer.awaitWritesIssuedBeforeCall(timeoutMillis = 2_000L))
            val captures = store.captureFiles()
            if (mode == DiagnosticsCaptureMode.OFF) {
                assertTrue(captures.isEmpty())
                assertTrue(store.exportFiles().isEmpty())
            } else {
                assertTrue(captures.isNotEmpty())
                assertTrue(captures.size <= 2)
                assertTrue(captures.all { it.length() <= config.captureBytesLimit })
                assertEquals(3, store.exportFiles().size)
                val export = store.exportFiles().last().readText()
                secrets.forEach { assertFalse("private data in persisted report", export.contains(it)) }
            }
            assertTrue(buffer.size <= 64)
            counters += DiagnosticsModeCounters(
                mode = mode,
                accepted = accepted.get(),
                dropped = writer.droppedWrites,
                persistedBytes = captures.sumOf { it.length() },
            )
        }
        val result = DiagnosticsOverheadBaseline.summarize(config, timings, counters)
        assertEquals(3, result.counters.size)
        assertEquals(0, result.counters.single { it.mode == DiagnosticsCaptureMode.OFF }.accepted)
        assertTrue(result.counters.single { it.mode == DiagnosticsCaptureMode.VERBOSE }.accepted >
            result.counters.single { it.mode == DiagnosticsCaptureMode.STANDARD }.accepted)
        DiagnosticsCaptureMode.entries.forEach { mode ->
            val formatting = result.phases.single {
                it.mode == mode && it.phase == DiagnosticsOverheadPhase.EVENT_FORMAT
            }
            assertEquals(20 * (if (mode == DiagnosticsCaptureMode.VERBOSE) 4 else 3), formatting.distribution.count)
            assertTrue(formatting.distribution.minNanos >= 0L)
            assertTrue(formatting.distribution.p95Nanos != null)
        }
        val output = result.toSafeText()
        secrets.forEach { assertFalse("private value escaped summary", output.contains(it)) }
        println("O2_JVM_OBSERVATION_ONLY\n" + output)
    }

    @Test
    fun saturatedWriterDropsExcessWithoutBlockingAndExportWaitsForAcceptedWrites() {
        val store = DiagnosticFileStore(
            rootDir = temporaryFolder.newFolder("saturation"),
            maxCaptureBytes = 128L,
        )
        val writer = DiagnosticWriteQueue(queueCapacity = 1)
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val producerFinished = CountDownLatch(1)
        assertTrue(writer.offer {
            running.countDown()
            release.await(5, TimeUnit.SECONDS)
            store.appendCapture("accepted-inflight")
        })
        try {
            assertTrue(running.await(2, TimeUnit.SECONDS))
            assertTrue(writer.offer { store.appendCapture("accepted-queued") })
            val producer = Thread {
                repeat(50) {
                    assertFalse(writer.offer { store.appendCapture("should-not-persist") })
                }
                producerFinished.countDown()
            }
            producer.start()
            assertTrue("producers must not wait for full writer", producerFinished.await(2, TimeUnit.SECONDS))
            assertEquals(50L, writer.droppedWrites)
            assertFalse(writer.awaitWritesIssuedBeforeCall(timeoutMillis = 0L))
        } finally {
            release.countDown()
        }
        assertTrue(writer.awaitWritesIssuedBeforeCall(timeoutMillis = 3_000L))
        val report = writer.runControl {
            store.createExport(
                metadataLines = listOf("droppedPersistenceWrites=" + writer.droppedWrites),
                recentEventLines = emptyList(),
                fileName = "saturated.txt",
            )
        }
        val text = report.readText()
        assertTrue(text.contains("accepted-inflight"))
        assertTrue(text.contains("accepted-queued"))
        assertFalse(text.contains("should-not-persist"))
        assertTrue(text.contains("droppedPersistenceWrites=50"))
        assertTrue(store.captureFiles().sumOf { it.length() } <= 256L)
    }

    @Test
    fun tracingNoOpSinkDoesNotAccidentallyBecomeDisabledCaptureMode() {
        val received = AtomicInteger()
        val tracer = StructuredTracer(
            sink = StructuredTraceSink.NO_OP,
            traceIdFactory = { "no-op" },
        )
        repeat(20) {
            tracer.startTrace(TraceFeature.PLAYBACK, "play_to_audible").complete(TraceOutcome.SUCCEEDED)
        }
        assertEquals(0, received.get())
        assertEquals(3, DiagnosticsCaptureMode.entries.size)
    }
}
