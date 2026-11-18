package com.music.bitchord.ui.components

/** The six explicitly approved Apple-style player actions, in visual order. */
internal enum class ApplePlayerMenuAction {
    LIBRARY, PLAYLIST, SHARE_SONG, SHARE_LYRICS, PLAY_NEXT, ADD_TO_QUEUE,
}

internal val APPLE_PLAYER_MENU_ACTIONS: List<ApplePlayerMenuAction> = listOf(
    ApplePlayerMenuAction.LIBRARY,
    ApplePlayerMenuAction.PLAYLIST,
    ApplePlayerMenuAction.SHARE_SONG,
    ApplePlayerMenuAction.SHARE_LYRICS,
    ApplePlayerMenuAction.PLAY_NEXT,
    ApplePlayerMenuAction.ADD_TO_QUEUE,
)
