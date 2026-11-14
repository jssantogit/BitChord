package com.music.bitchord

/** A genre is a pushed Explore page: its Back belongs to the global top bar,
 * never to HomeScreen's in-list header (which overlapped the overflow circle).
 */
internal fun shouldShowExploreGenreTopBarBack(
    selectedTab: Int,
    exploreTab: Int,
    hasGenre: Boolean,
    detailOpen: Boolean,
): Boolean = selectedTab == exploreTab && hasGenre && !detailOpen
