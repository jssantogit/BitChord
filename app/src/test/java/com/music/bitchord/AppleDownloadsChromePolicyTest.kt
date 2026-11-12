package com.music.bitchord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleDownloadsChromePolicyTest {
    @Test fun downloadsUsesIndependentCircularControlsOnlyOnItsOwnPage() {
        assertTrue(shouldUseAppleDownloadsChrome("local:downloads", settingsOpen = false))
        assertFalse(shouldUseAppleDownloadsChrome("local:all", settingsOpen = false))
        assertFalse(shouldUseAppleDownloadsChrome("local:playlist:123", settingsOpen = false))
        assertFalse(shouldUseAppleDownloadsChrome(null, settingsOpen = false))
        assertFalse(shouldUseAppleDownloadsChrome("local:downloads", settingsOpen = true))
    }
}
