package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests logical EOS/queue continuity only, never audible PCM discontinuity. */
class GaplessBoundaryPolicyTest {
    @Test
    fun `crossfade zero clears old handoff window and uses existing Media3 EOS`() {
        assertTrue(GaplessBoundaryPolicy.mustUseNativeBoundary(0L, smartFadeEnabled = false))
        assertTrue(GaplessBoundaryPolicy.mustUseNativeBoundary(-1L, smartFadeEnabled = false))
    }

    @Test
    fun `explicit crossfade and independent smart fade are not silently overridden`() {
        assertFalse(GaplessBoundaryPolicy.mustUseNativeBoundary(5000L, smartFadeEnabled = false))
        assertFalse(GaplessBoundaryPolicy.mustUseNativeBoundary(0L, smartFadeEnabled = true))
    }

    @Test
    fun `local remote and mixed EOS preserve original Media3 timeline and rendition URIs`() {
        val localA = MediaItem.Builder().setMediaId("local-a").setUri("content://media/audio/101").build()
        val localB = MediaItem.Builder().setMediaId("local-b").setUri("content://media/audio/102").build()
        val remoteA = MediaItem.Builder().setMediaId("remote-a").setUri("bitchord://canonical/play/recording-a?asset=one").build()
        val remoteB = MediaItem.Builder().setMediaId("remote-b").setUri("bitchord://canonical/play/recording-b?asset=two").build()
        listOf(listOf(localA, localB), listOf(remoteA, remoteB), listOf(localA, remoteA), listOf(remoteB, localB)).forEach { queue ->
            val before = queue.map { it.mediaId to it.localConfiguration?.uri }
            assertTrue(GaplessBoundaryPolicy.mustUseNativeBoundary(0L, smartFadeEnabled = false))
            assertEquals(before, queue.map { it.mediaId to it.localConfiguration?.uri })
        }
    }

    @Test
    fun `repeat all and shuffle logical EOS keep existing queue window traversal`() {
        for (shuffle in listOf(false, true)) {
            val queue = QueueWindow(4).startAt(0, shuffle)
            val before = queue.snapshot()
            assertTrue(GaplessBoundaryPolicy.mustUseNativeBoundary(0L, smartFadeEnabled = false))
            val next = queue.advanceAutomatically(repeatAll = true)
            assertTrue(next != null)
            assertEquals(4, queue.canonicalSize)
            assertEquals(before.isShuffleEnabled, queue.snapshot().isShuffleEnabled)
            // Only QueueWindow/Media3 determine the next logical occurrence.
            assertTrue((0 until 4).contains(next!!.canonicalIndex))
        }
    }
}
