package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalQueueIdentityTest {

    @Test
    fun `recording identity may exist without selecting a rendition`() {
        val identity = CanonicalQueueIdentity(recordingId = "recording-1")

        assertEquals("recording-1", identity.recordingId)
        assertEquals(null, identity.mediaAssetId)
        assertEquals(null, identity.providerKey)
    }

    @Test
    fun `rendition reference requires asset and provider together`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalQueueIdentity(
                recordingId = "recording-1",
                mediaAssetId = "asset-1",
                providerKey = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalQueueIdentity(
                recordingId = "recording-1",
                mediaAssetId = null,
                providerKey = "provider-1",
            )
        }
    }

    @Test
    fun `canonical identity parts must not be blank`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalQueueIdentity(recordingId = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalQueueIdentity(
                recordingId = "recording-1",
                mediaAssetId = "",
                providerKey = "provider-1",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalQueueIdentity(
                recordingId = "recording-1",
                mediaAssetId = "asset-1",
                providerKey = "",
            )
        }
    }
}
