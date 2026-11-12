package com.music.bitchord.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Never offer an original BitChord release as an update to this fork. */
class AppUpdateCheckerForkSafetyTest {
    @Test fun updateMetadataMustComeFromThisForkOnly() {
        assertEquals(
            "https://api.github.com/repos/jssantogit/BitChord/releases/latest",
            AppUpdateChecker.LATEST_RELEASE_URL,
        )
        assertFalse(AppUpdateChecker.LATEST_RELEASE_URL.contains("kushagrasinghx"))
    }
}
