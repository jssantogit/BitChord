package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A preview must hide its source only while a successful image copy is lifted. */
class HeldItemLiftStateTest {
    @Test fun liftingHidesSourceButDismissRestoresIt() {
        val held = HeldItem(Rect(0f, 0f, 40f, 40f))
        assertFalse(held.isLifted)
        held.markLifted()
        assertTrue(held.isLifted)
        held.releaseLift()
        assertFalse(held.isLifted)
    }

    @Test fun delayedBitmapCannotRehideSourceAfterEarlyDismiss() {
        val held = HeldItem(Rect(0f, 0f, 40f, 40f))
        held.releaseLift()
        held.markLifted()
        assertFalse(held.isLifted)
    }
}
