package com.music.bitchord.playback

import com.music.bitchord.diagnostics.BoundedPerformanceProbe
import com.music.bitchord.diagnostics.BoundedPerformanceSample
import com.music.bitchord.diagnostics.DiagnosticCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPerformanceProbeTest {

    @Test
    fun `playback operation is recorded as a bounded player sample`() {
        val times = listOf(100L, 145L).iterator()
        val samples = mutableListOf<BoundedPerformanceSample>()
        val bounded = BoundedPerformanceProbe(
            nowNanos = { times.next() },
            sink = samples::add,
        )
        val probe = PlaybackPerformanceProbe(bounded)

        val result = probe.measure(PlaybackPerformanceOperation.SEEK) {
            "done"
        }

        assertEquals("done", result)
        assertEquals(
            BoundedPerformanceSample(
                category = DiagnosticCategory.PLAYER,
                name = "runtime_playback_seek",
                durationNanos = 45L,
                metadata = emptyMap(),
            ),
            samples.single(),
        )
    }

    @Test
    fun `operation names stay stable and low cardinality`() {
        assertEquals(
            listOf(
                "runtime_playback_seek",
                "runtime_playback_queue_mutation",
                "runtime_playback_queue_rebuild",
                "runtime_playback_track_transition",
            ),
            PlaybackPerformanceOperation.entries.map { it.eventName },
        )
    }
}
