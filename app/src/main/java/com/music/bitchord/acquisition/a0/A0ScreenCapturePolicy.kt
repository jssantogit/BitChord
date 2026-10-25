package com.music.bitchord.acquisition.a0

/**
 * The devDebug download picker has no visible password field. Its search box,
 * peers and candidate list are intentionally screenshot-friendly for QA.
 * The separate legacy login probe keeps FLAG_SECURE while credentials can
 * be entered. The account Settings screen has its own secure-window policy.
 */
internal object A0ScreenCapturePolicy {
    fun requiresSecureWindow(
        fromSongDownload:Boolean,
        credentialsVisible:Boolean=!fromSongDownload
    ):Boolean = credentialsVisible
}
