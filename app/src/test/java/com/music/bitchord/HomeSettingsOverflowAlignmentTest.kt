package com.music.bitchord

import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's floating ⋯ follows its large in-list heading, then returns to top chrome. */
class HomeSettingsOverflowAlignmentTest {
    @Test fun settingsLauncherAlignsWithTheUnscrolledHomeHeading() {
        assertEquals(34f, homeSettingsOverflowOffsetDp(true, 0, 0f))
    }

    @Test fun scrollingMovesLauncherSmoothlyBackIntoTheToolbar() {
        assertEquals(18f, homeSettingsOverflowOffsetDp(true, 0, 16f))
        assertEquals(0f, homeSettingsOverflowOffsetDp(true, 0, 40f))
        assertEquals(0f, homeSettingsOverflowOffsetDp(true, 1, 0f))
    }

    @Test fun otherPagesAndNestedDetailsNeverReceiveHomeOffset() {
        assertEquals(0f, homeSettingsOverflowOffsetDp(false, 0, 0f))
    }
}
