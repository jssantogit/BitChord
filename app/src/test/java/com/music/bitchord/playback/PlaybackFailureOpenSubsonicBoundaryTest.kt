package com.music.bitchord.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaybackFailureOpenSubsonicBoundaryTest {

    @Test
    fun `provider owned account unavailable IOException auto advances without legacy resolver`() {
        val disposition = classifyPlaybackFailure(
            canonicalRemote = true,
            localUriScheme = "bitchord",
            errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            neverStarted = true,
        )

        assertEquals(PlaybackFailureDisposition.AUTO_ADVANCE, disposition)
        assertNotEquals(PlaybackFailureDisposition.LEGACY_RECOVERY, disposition)
    }

    @Test
    fun `provider owned account failure after audible playback stays terminal`() {
        val disposition = classifyPlaybackFailure(
            canonicalRemote = true,
            localUriScheme = "bitchord",
            errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            neverStarted = false,
        )

        assertEquals(PlaybackFailureDisposition.TERMINAL, disposition)
        assertNotEquals(PlaybackFailureDisposition.LEGACY_RECOVERY, disposition)
    }
}
