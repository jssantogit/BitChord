package com.music.bitchord.ui.components

import com.music.bitchord.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Protects the 7.0.0 AM-DECOMP vector -> actual UI slot mappings. */
class AppleMusicOriginalIconsTest {
    @Test
    fun allFiveVisibleTabsUseTheirOwnRecoveredAppleMusicVector() {
        val icons = (0..4).map(::appleMusicTabIconForVisualIndex)
        assertEquals(
            listOf(
                R.drawable.apple_music_tab_home,
                R.drawable.apple_music_tab_explore,
                R.drawable.apple_music_tab_radio,
                R.drawable.apple_music_tab_library,
                R.drawable.apple_music_tab_search,
            ),
            icons,
        )
        assertEquals(5, icons.distinct().size)
    }

    @Test
    fun pauseAndPlayUseOriginalMiniPlayerAssetsInTheCorrectState() {
        assertEquals(R.drawable.apple_music_mp_pause, appleMusicMiniPlayerPlayPauseIcon(true))
        assertEquals(R.drawable.apple_music_mp_play, appleMusicMiniPlayerPlayPauseIcon(false))
        assertNotEquals(appleMusicMiniPlayerPlayPauseIcon(true), appleMusicMiniPlayerPlayPauseIcon(false))
        assertNotEquals(R.drawable.apple_music_mp_next, appleMusicMiniPlayerPlayPauseIcon(true))
    }
}
