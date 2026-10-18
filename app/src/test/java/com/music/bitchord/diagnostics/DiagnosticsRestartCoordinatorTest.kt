package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRestartCoordinatorTest {
    @Test
    fun `restart capture persists before scheduling and terminating`() {
        val calls = mutableListOf<String>()
        val coordinator = DiagnosticsRestartCoordinator(
            persistCapture = {
                calls += "persist"
                true
            },
            scheduleRestart = { calls += "schedule" },
            terminateProcess = { calls += "terminate" },
        )

        assertTrue(coordinator.restartAndCapture())
        assertEquals(listOf("persist", "schedule", "terminate"), calls)
    }

    @Test
    fun `restart aborts when capture persistence fails`() {
        val calls = mutableListOf<String>()
        val coordinator = DiagnosticsRestartCoordinator(
            persistCapture = {
                calls += "persist"
                false
            },
            scheduleRestart = { calls += "schedule" },
            terminateProcess = { calls += "terminate" },
        )

        assertFalse(coordinator.restartAndCapture())
        assertEquals(listOf("persist"), calls)
    }
}
