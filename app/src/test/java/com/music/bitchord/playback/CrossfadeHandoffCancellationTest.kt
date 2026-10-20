package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeHandoffCancellationTest {
    @Test
    fun `output route switch must retire non-session player without waiting for next frame`() {
        assertTrue(CrossfadeHandoffCancellation.settleImmediately(CrossfadeInterruption.ROUTE_CHANGE))
    }

    @Test
    fun `controller release must retire armed or overlapping spare before ticker stops`() {
        assertTrue(CrossfadeHandoffCancellation.settleImmediately(CrossfadeInterruption.CONTROLLER_RELEASE))
    }

    @Test
    fun `skip seek and errors keep existing controlled bail instead of jumping controllers`() {
        for (reason in listOf(
            CrossfadeInterruption.MANUAL_SKIP,
            CrossfadeInterruption.SEEK,
            CrossfadeInterruption.PLAYER_ERROR,
        )) {
            assertFalse(CrossfadeHandoffCancellation.settleImmediately(reason))
        }
    }

    @Test
    fun `route cancellation never creates a new selection or rewrites existing queue identity`() {
        val old = MediaItem.Builder().setMediaId("recording-a").setUri("content://media/audio/51").build()
        val selected = MediaItem.Builder().setMediaId("recording-b").setUri("bitchord://canonical/play/recording-b").build()
        val upcoming = MediaItem.Builder().setMediaId("recording-c").setUri("content://media/audio/52").build()
        val materialized = listOf(old, selected, upcoming)
        val unchanged = materialized.map { it.mediaId to it.localConfiguration?.uri }
        assertTrue(CrossfadeHandoffCancellation.settleImmediately(CrossfadeInterruption.ROUTE_CHANGE))
        assertEquals(unchanged, materialized.map { it.mediaId to it.localConfiguration?.uri })
    }
}
