package com.music.bitchord.data.library

/** Only the queue header gets the Apple queue-specific action sheet. */
internal fun shouldUseAppleQueueSongActions(fromPlayer: Boolean, fromQueueHeader: Boolean): Boolean =
    fromPlayer && fromQueueHeader

/**
 * The entire player has a dedicated first-party rating star. Do not duplicate
 * the editor inside its overflow menu. Other song-row menus remain editable.
 */
internal fun shouldShowRatingEditorInSongActions(fromPlayer: Boolean): Boolean = !fromPlayer
