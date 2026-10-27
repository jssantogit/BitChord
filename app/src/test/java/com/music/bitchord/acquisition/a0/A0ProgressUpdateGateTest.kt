package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0ProgressUpdateGateTest {
    @Test fun coalescesBurstsButPublishesFirstAndLaterUpdates() {
        val gate=A0ProgressUpdateGate(700L)
        assertTrue(gate.shouldPublish(1000L))
        assertFalse(gate.shouldPublish(1001L))
        assertFalse(gate.shouldPublish(1699L))
        assertTrue(gate.shouldPublish(1700L))
        assertFalse(gate.shouldPublish(2000L))
        assertTrue(gate.shouldPublish(2400L))
    }

    @Test fun allowsNextUpdateAfterClockResetWithoutBusyLoop() {
        val gate=A0ProgressUpdateGate(700L)
        assertTrue(gate.shouldPublish(10000L))
        assertTrue(gate.shouldPublish(300L))
        assertFalse(gate.shouldPublish(999L))
        assertTrue(gate.shouldPublish(1000L))
    }

    @Test fun invalidIntervalRejected() {
        try { A0ProgressUpdateGate(0) ; fail("Interval must be positive") }
        catch (_: IllegalArgumentException) {}
    }
}
