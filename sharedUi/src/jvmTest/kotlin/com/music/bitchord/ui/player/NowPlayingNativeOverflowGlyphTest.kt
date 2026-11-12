package com.music.bitchord.ui.player

import org.junit.Assert.assertSame
import org.junit.Test

class NowPlayingNativeOverflowGlyphTest {
    @Test fun landscapePlayerOverflowHasNoInnerPlatterOrMaterialVariant() {
        assertSame(AppleQueueVectors.MorePlain, nativeLandscapePlayerOverflowGlyph())
    }

    @Test fun fallbackNowPlayingCircleHasTheSameUnplatteredOverflowAsTheQueue() {
        assertSame(AppleQueueVectors.MorePlain, nativeNowPlayingOverflowGlyph())
    }
}
