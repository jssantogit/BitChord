package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * Regression for Android: SVG resources used by painterResource throw
 * IllegalStateException when opening the player. Native ImageVectors must be
 * instantiated instead of platform SVG painter resources.
 */
class AppleQueueVectorCompatibilityTest {
    @Test fun allFourOriginalQueueGlyphsAreNativeImageVectors() {
        val icons = listOf(
            AppleQueueVectors.Favorite, AppleQueueVectors.Favorited,
            AppleQueueVectors.More, AppleQueueVectors.DragHandle,
        )
        icons.forEach {
            assertEquals(28f, it.viewportWidth, 0.001f)
            assertEquals(28f, it.viewportHeight, 0.001f)
        }
        assertNotSame(AppleQueueVectors.Favorite, AppleQueueVectors.Favorited)
    }
}
