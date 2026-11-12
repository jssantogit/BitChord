package com.music.bitchord.ui.screens

/**
 * Apple Music Android's observed Library landing order. The composition has
 * real Bitchord sources behind it: verified saved cards, working category
 * routes, and chronological local additions. None of these sections
 * generates artwork or a placeholder media item to fill the reference grid.
 */
internal enum class AppleLibraryLandingSection { PINS, CATEGORIES, RECENT }

internal fun appleLibraryLandingSections(
    hasPins: Boolean,
    hasCategories: Boolean,
    hasRecent: Boolean,
): List<AppleLibraryLandingSection> = buildList {
    if (hasPins) add(AppleLibraryLandingSection.PINS)
    if (hasCategories) add(AppleLibraryLandingSection.CATEGORIES)
    if (hasRecent) add(AppleLibraryLandingSection.RECENT)
}
