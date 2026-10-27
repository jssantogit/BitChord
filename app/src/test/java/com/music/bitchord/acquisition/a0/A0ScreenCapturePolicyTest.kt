package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0ScreenCapturePolicyTest {
    @Test fun downloadSearchAndCandidatePickerPermitScreenshots() {
        assertFalse(A0ScreenCapturePolicy.requiresSecureWindow(fromSongDownload=true))
    }

    @Test fun legacySearchAfterCredentialsClearedPermitsScreenshots() {
        assertFalse(A0ScreenCapturePolicy.requiresSecureWindow(
            fromSongDownload=false, credentialsVisible=false))
    }

    @Test fun legacyAccountPasswordLoginStillBlocksScreenshots() {
        assertTrue(A0ScreenCapturePolicy.requiresSecureWindow(fromSongDownload=false))
    }
}
