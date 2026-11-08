package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ApplePlayerMenuPolicyTest {
    @Test fun onlyApprovedActionsAppearAndInAppleOrder() {
        assertEquals(
            listOf(
                ApplePlayerMenuAction.LIBRARY,
                ApplePlayerMenuAction.PLAYLIST,
                ApplePlayerMenuAction.SHARE_SONG,
                ApplePlayerMenuAction.SHARE_LYRICS,
                ApplePlayerMenuAction.PLAY_NEXT,
                ApplePlayerMenuAction.ADD_TO_QUEUE,
            ),
            APPLE_PLAYER_MENU_ACTIONS,
        )
        assertEquals(6, APPLE_PLAYER_MENU_ACTIONS.distinct().size)
    }

    @Test fun noUnsupportedPlayerOptionsAreIntroduced() {
        val names = APPLE_PLAYER_MENU_ACTIONS.map { it.name }
        listOf("CREDITS", "STATION", "SLEEP_TIMER", "FAVORITE", "SUGGEST_LESS")
            .forEach { assertFalse(it in names) }
    }
}
