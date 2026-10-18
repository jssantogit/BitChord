package com.music.bitchord

import com.music.bitchord.ui.player.CanonicalPlaybackArtworkIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalPlaybackArtworkContractTest {
    @Test
    fun `artwork key is canonical recording plus selected media asset only`() {
        val first = CanonicalPlaybackArtworkIdentity(
            recordingId = "recording-1",
            mediaAssetId = "asset-a",
            providerKey = "opensubsonic:home",
        )
        val sameRenditionDifferentRouting = first.copy(providerKey = "provider-routing-must-not-be-identity")
        val anotherRendition = first.copy(mediaAssetId = "asset-b")

        assertEquals(first.key, sameRenditionDifferentRouting.key)
        assertNotEquals(first.key, anotherRendition.key)
        assertEquals("recording-1", first.key.recordingId)
        assertEquals("asset-a", first.key.mediaAssetId)
    }

    @Test
    fun `identity does not accept provider native identifiers as artwork key material`() {
        val identity = CanonicalPlaybackArtworkIdentity(
            recordingId = "recording-canonical",
            mediaAssetId = "asset-canonical",
            providerKey = "opensubsonic:home",
        )

        val publicKey = identity.key.toString()
        assertEquals("recording-canonical", identity.key.recordingId)
        assertEquals("asset-canonical", identity.key.mediaAssetId)
        assertNull(CanonicalPlaybackArtworkIdentity::class.java.declaredFields.firstOrNull { it.name == "coverArtId" })
        assertNull(CanonicalPlaybackArtworkIdentity::class.java.declaredFields.firstOrNull { it.name == "sourceKey" })
        assertNull(CanonicalPlaybackArtworkIdentity::class.java.declaredFields.firstOrNull { it.name == "providerSongId" })
        assertEquals(false, publicKey.contains("cover"))
    }
}
