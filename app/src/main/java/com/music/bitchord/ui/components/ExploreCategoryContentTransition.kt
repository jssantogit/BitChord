package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.runtime.Composable

/**
 * Explore owns one stable host while switching between its genre index and a
 * selected genre. Both the incoming and outgoing destinations receive their
 * own immutable category snapshot; never redraw the outgoing index using the
 * newly selected category, which caused a visual duplicate/flash.
 *
 * Keep the outgoing genre out of the next genre's initial frame. The global top bar, player
 * and bottom navigation remain outside this content transition.
 */
/** Explore's nested destination changes use the isolated page policy. */
internal fun exploreCategoryContentTransform(reduceMotion: Boolean): ContentTransform =
    isolatedPageContentTransform(reduceMotion)

@Composable
internal fun <T> ExploreCategoryContentTransition(
    category: T?,
    reduceMotion: Boolean,
    content: @Composable (T?) -> Unit,
) {
    AnimatedContent(
        targetState = category,
        transitionSpec = { exploreCategoryContentTransform(reduceMotion) },
        label = "explore-category-content",
    ) { visibleCategory ->
        content(visibleCategory)
    }
}
