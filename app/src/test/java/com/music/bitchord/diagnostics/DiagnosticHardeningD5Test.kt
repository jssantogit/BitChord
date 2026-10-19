package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticHardeningD5Test {
    @get:Rule val temp = TemporaryFolder()

    @Test fun oversizedUtf8CaptureNeverLeavesBrokenCharacterOrUnterminatedLine() {
        val store = DiagnosticFileStore(temp.newFolder(), 7L)
        store.appendCapture("🙂".repeat(12))
        val decoded = store.currentCaptureFile().readText(Charsets.UTF_8)
        assertTrue(decoded.endsWith("\n"))
        assertFalse(decoded.contains("\uFFFD"))
        assertTrue(store.currentCaptureFile().length() <= 7L)
    }

    @Test fun concurrentSaturationAccountsForAllAttemptsAndRemainsBounded() {
        val store = DiagnosticFileStore(temp.newFolder(), 96L)
        val queue = DiagnosticWriteQueue(queueCapacity = 2)
        val accepted = AtomicInteger()
        val dropped = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val times = Collections.synchronizedList(mutableListOf<Long>())
        repeat(8) { producer ->
            Thread {
                start.await(2, TimeUnit.SECONDS)
                repeat(40) { index ->
                    val begin = System.nanoTime()
                    if (queue.offer { store.appendCapture("producer=$producer event=$index") }) {
                        accepted.incrementAndGet()
                    } else dropped.incrementAndGet()
                    times += System.nanoTime() - begin
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(8, TimeUnit.SECONDS))
        assertTrue(queue.awaitWritesIssuedBeforeCall(8_000L))
        assertEquals(320, accepted.get() + dropped.get())
        assertEquals(dropped.get().toLong(), queue.droppedWrites)
        assertEquals(0L, queue.failedWrites)
        assertTrue(store.captureFiles().size <= 2)
        assertTrue(store.captureFiles().all { it.length() <= 96L })
        assertEquals(320, times.size)

        // Reuse O2's actual distribution, not a synthetic percentage speedup.
        val report = DiagnosticsOverheadBaseline.summarize(
            DiagnosticsOverheadConfig(320, 0, 8, 96, 2),
            times.map {
                DiagnosticsOverheadObservation(
                    DiagnosticsCaptureMode.STANDARD,
                    DiagnosticsOverheadPhase.WRITER_OFFER,
                    it,
                )
            },
            listOf(DiagnosticsModeCounters(
                DiagnosticsCaptureMode.STANDARD,
                accepted.get(),
                queue.droppedWrites,
                store.captureFiles().sumOf { it.length() },
            )),
        )
        assertEquals(320, report.phases.single().distribution.count)
        assertTrue(report.phases.single().distribution.p95Nanos != null)
        assertFalse(report.toSafeText().contains("speedup"))
    }

    @Test fun processRestartRotationAndCorruptLegacyCaptureRemainExplainable() {
        val dir = temp.newFolder()
        val initial = DiagnosticFileStore(dir, 48L)
        repeat(30) { initial.appendCapture("seq=$it") }
        initial.writeLastCrash("bounded crash")
        val restarted = DiagnosticFileStore(dir, 48L)
        assertEquals(2, restarted.captureFiles().size)
        assertTrue(restarted.captureFiles().all { it.length() <= 48L })
        assertEquals("bounded crash", restarted.lastCrashFile().readText())
        assertEquals(0L, restarted.captureRotationsSinceOpen)

        // A persisted fragment from an earlier release may not have a schema.
        restarted.currentCaptureFile().appendText("partial-versionless\n")
        val report = restarted.createExport(
            metadataLines = emptyList(),
            recentEventLines = emptyList(),
            fileName = "restart.txt",
            reportV2Input = DiagnosticReportV2Input(
                recentEvents = emptyList(),
                flushComplete = false,
                rejectedWrites = 2,
                evictedMemoryEvents = 0,
            ),
            includeJsonl = true,
        ).readText()
        assertTrue(report.contains("integrity=partial_flush_timeout"))
        assertTrue(report.contains("unparsed_legacy_capture_lines="))
        assertTrue(report.contains("io_failures=unknown"))
        restarted.clear()
        assertTrue(restarted.captureFiles().isEmpty())
        assertTrue(restarted.exportFiles().isEmpty())
    }
}
