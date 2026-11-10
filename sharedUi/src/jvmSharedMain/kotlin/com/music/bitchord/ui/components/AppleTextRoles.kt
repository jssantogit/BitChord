package com.music.bitchord.ui.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Component-level typography roles shared across the native Android pages.
 *
 * The Android app's BitChordTheme selects the SF Pro Display family and
 * Material3 type scale. SharedUi uses those styles rather than hardcoding
 * identical font sizes again, so language, fontScale and future theme edits
 * flow through every screen that uses these roles.
 *
 * The Library root's 36sp heading is an existing screenshot-calibrated
 * Bitchord exception. am-descomp's 28sp Title1 describes Library *details*,
 * not this landing title; never conflate the two.
 */
internal object AppleTextRoles {
    fun libraryLandingTitle(base: TextStyle): TextStyle =
        base.copy(fontSize = 36.sp, fontWeight = FontWeight.W700)

    fun categoryLabel(base: TextStyle): TextStyle = base

    fun formInput(base: TextStyle): TextStyle = base
}
