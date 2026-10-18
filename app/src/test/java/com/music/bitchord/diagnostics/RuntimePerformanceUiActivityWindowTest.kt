package com.music.bitchord.diagnostics

import com.music.bitchord.ui.performance.RuntimePerformanceUiSignal
import com.music.bitchord.ui.performance.RuntimePerformanceUiStateKey
import com.music.bitchord.ui.performance.RuntimePerformanceUiStateValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceUiActivityWindowTest {

    @Test
    fun `inactive capture keeps latest state but does not count churn`() {
        val snapshots = mutableListOf<RuntimePerformanceUiActivitySnapshot>()
        val window = RuntimePerformanceUiActivityWindow("now_playing", snapshots::add)

        window.setState(RuntimePerformanceUiStateKey.DYNAMIC_BLUR, RuntimePerformanceUiStateValue.ENABLED)
        window.record(RuntimePerformanceUiSignal.NOW_PLAYING_RECOMPOSITION)
        window.flush()

        assertTrue(snapshots.isEmpty())

        window.setCaptureActive(true)
        window.record(RuntimePerformanceUiSignal.NOW_PLAYING_RECOMPOSITION)
        window.flush()

        assertEquals(1, snapshots.size)
        assertEquals(1L, snapshots.single().signalCounts[RuntimePerformanceUiSignal.NOW_PLAYING_RECOMPOSITION])
        assertEquals(
            RuntimePerformanceUiStateValue.ENABLED,
            snapshots.single().states[RuntimePerformanceUiStateKey.DYNAMIC_BLUR],
        )
        assertTrue(snapshots.single().stateChangeCounts.isEmpty())
    }

    @Test
    fun `active window aggregates signals and state transitions with fixed metadata`() {
        val snapshots = mutableListOf<RuntimePerformanceUiActivitySnapshot>()
        val window = RuntimePerformanceUiActivityWindow("now_playing", snapshots::add)
        window.setState(RuntimePerformanceUiStateKey.CANVAS_MODE, RuntimePerformanceUiStateValue.INACTIVE)
        window.setCaptureActive(true)

        repeat(3) { window.record(RuntimePerformanceUiSignal.NOW_PLAYING_RECOMPOSITION) }
        repeat(2) { window.record(RuntimePerformanceUiSignal.ARTWORK_BACKDROP_RECOMPOSITION) }
        window.record(RuntimePerformanceUiSignal.ARTWORK_MESH_UPDATE)
        window.setState(RuntimePerformanceUiStateKey.CANVAS_MODE, RuntimePerformanceUiStateValue.ACTIVE)
        window.setState(RuntimePerformanceUiStateKey.CANVAS_MODE, RuntimePerformanceUiStateValue.ACTIVE)
        window.setState(RuntimePerformanceUiStateKey.DYNAMIC_BLUR, RuntimePerformanceUiStateValue.REDUCED)
        window.flush()

        val snapshot = snapshots.single()
        assertEquals("now_playing", snapshot.context)
        assertEquals(3L, snapshot.signalCounts[RuntimePerformanceUiSignal.NOW_PLAYING_RECOMPOSITION])
        assertEquals(2L, snapshot.signalCounts[RuntimePerformanceUiSignal.ARTWORK_BACKDROP_RECOMPOSITION])
        assertEquals(1L, snapshot.signalCounts[RuntimePerformanceUiSignal.ARTWORK_MESH_UPDATE])
        assertEquals(1L, snapshot.stateChangeCounts[RuntimePerformanceUiStateKey.CANVAS_MODE])
        assertEquals(1L, snapshot.stateChangeCounts[RuntimePerformanceUiStateKey.DYNAMIC_BLUR])
        assertEquals(
            mapOf(
                "context" to "now_playing",
                "nowPlayingRecompositions" to "3",
                "artworkBackdropRecompositions" to "2",
                "artworkMeshUpdates" to "1",
                "canvasMode" to "active",
                "canvasModeChanges" to "1",
                "dynamicBlur" to "reduced",
                "dynamicBlurChanges" to "1",
            ),
            snapshot.toDiagnosticMetadata(),
        )
    }

    @Test
    fun `context change flushes the old bounded activity window`() {
        val snapshots = mutableListOf<RuntimePerformanceUiActivitySnapshot>()
        val window = RuntimePerformanceUiActivityWindow("home", snapshots::add)
        window.setCaptureActive(true)
        window.record(RuntimePerformanceUiSignal.NOW_PLAYING_RECOMPOSITION)

        window.setContext("now_playing")

        assertEquals(1, snapshots.size)
        assertEquals("home", snapshots.single().context)
    }

    @Test
    fun `stopping capture discards an unflushed partial window`() {
        val snapshots = mutableListOf<RuntimePerformanceUiActivitySnapshot>()
        val window = RuntimePerformanceUiActivityWindow("now_playing", snapshots::add)
        window.setCaptureActive(true)
        window.record(RuntimePerformanceUiSignal.CANVAS_FRAME_MESH_UPDATE)

        window.setCaptureActive(false)
        window.setCaptureActive(true)
        window.flush()

        assertTrue(snapshots.isEmpty())
    }
}
