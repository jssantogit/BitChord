package com.music.bitchord.ui.player

import org.junit.Assert.assertSame
import org.junit.Test

class NowPlayingNativeOverflowGlyphTest {
    @Test fun fallbackNowPlayingCircleHasTheSameUnplatteredOverflowAsTheQueue() {
        assertSame(AppleQueueVectors.MorePlain, nativeNowPlayingOverflowGlyph())
    }
}
