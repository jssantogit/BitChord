package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeFrameBudgetTest {

    @Test
    fun `frame budget follows the active display refresh rate`() {
        assertEquals(16_666_667L, frameBudgetNanosForRefreshRate(60f))
        assertEquals(11_111_111L, frameBudgetNanosForRefreshRate(90f))
        assertEquals(8_333_333L, frameBudgetNanosForRefreshRate(120f))
    }

    @Test
    fun `invalid refresh rate falls back to sixty hertz`() {
        assertEquals(16_666_667L, frameBudgetNanosForRefreshRate(0f))
        assertEquals(16_666_667L, frameBudgetNanosForRefreshRate(Float.NaN))
        assertEquals(16_666_667L, frameBudgetNanosForRefreshRate(Float.POSITIVE_INFINITY))
    }
}
