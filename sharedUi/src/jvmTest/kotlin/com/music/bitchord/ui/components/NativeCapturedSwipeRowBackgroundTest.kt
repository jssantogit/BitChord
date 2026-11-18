package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCapturedSwipeRowBackgroundTest {
    @Test fun unselectedSwipeRowKeepsThePageCoverInsideItsSnapshot() {
        val cover = Color(0xFF16494A)
        assertEquals(cover, nativeCapturedSwipeRowBackground(cover, Color.Transparent))
    }

    @Test fun selectedSwipeRowCapturesThePageCoverAndSelectionTintTogether() {
        val cover = Color(0xFF16494A)
        val selected = Color.White.copy(alpha = 0.14f)
        assertEquals(selected.compositeOver(cover), nativeCapturedSwipeRowBackground(cover, selected))
    }
}
