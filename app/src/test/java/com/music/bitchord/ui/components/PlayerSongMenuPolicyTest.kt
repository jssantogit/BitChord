package com.music.bitchord.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSongMenuPolicyTest {
    @Test fun menuKeepsSafetyEscapeButNotFiveRemovedActions() {
        assertTrue(PlayerSongMenuPolicy.offers(PlayerMenuExtra.REVERT_ORIGINAL))
        listOf(
            PlayerMenuExtra.UPGRADE_QUALITY,
            PlayerMenuExtra.CONVERT_VIDEO,
            PlayerMenuExtra.SLEEP_TIMER,
            PlayerMenuExtra.COPY_LOG,
            PlayerMenuExtra.LYRICS_OFFSET,
        ).forEach { assertFalse("unexpected menu extra: $it", PlayerSongMenuPolicy.offers(it)) }
    }
}
