package com.music.bitchord.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppleSettingsPopupMetricsTest {
    @Test fun oneActionSettingsPlateUsesTheCompactOverflowGeometry() {
        assertEquals(200.dp, APPLE_SETTINGS_SINGLE_ACTION_WIDTH)
        assertFalse(APPLE_SETTINGS_SINGLE_ACTION_USES_LARGE_ROWS)
    }
}
