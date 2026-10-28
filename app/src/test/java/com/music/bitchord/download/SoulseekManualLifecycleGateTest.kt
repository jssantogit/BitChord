package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

class SoulseekManualLifecycleGateTest {
    @Test fun returningToForegroundNeverReauthorizesAnOldWorker() {
        val gate=SoulseekManualLifecycleGate()
        val first=gate.token()
        assertTrue(gate.isCurrent(first))
        gate.revoke()
        assertFalse(gate.isCurrent(first))
        val second=gate.token()
        assertTrue(gate.isCurrent(second))
        assertNotEquals(first,second)
        gate.revoke()
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(second))
    }
}
