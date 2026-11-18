package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/** Native content remains painted; holding a card cannot replace it with a bitmap. */
class HeldItemLiftStateTest {
    @Test fun aHoldOnlyRecordsTheOriginalBounds() {
        val original = Rect(24f, 100f, 344f, 420f)
        val held = HeldItem(original)
        assertEquals(original, held.bounds)
        // A dismissal cannot leave a hidden source or a duplicated snapshot.
        held.releaseLift()
        assertEquals(original, held.bounds)
    }
}
