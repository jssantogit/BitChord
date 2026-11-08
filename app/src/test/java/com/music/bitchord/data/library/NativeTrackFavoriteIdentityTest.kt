package com.music.bitchord.data.library

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeTrackFavoriteIdentityTest {
    private fun song(id: String, local: String? = null) =
        Song(videoId = id, title = "Title", artist = "Artist", thumbnailUrl = null, localUri = local)

    @Test fun theIdentityDoesNotDependOnTrackTitleArtistOrCover() {
        val original = song("media:unique")
        val edited = original.copy(title = "Changed", artist = "Other", thumbnailUrl = "https://cover.example")
        assertEquals(nativeTrackFavoriteKey(original), nativeTrackFavoriteKey(edited))
        assertEquals(64, nativeTrackFavoriteKey(original)?.length)
    }

    @Test fun exactSourceIdsDifferAndPrivateUrlsAreNotStored() {
        assertNotEquals(
            nativeTrackFavoriteKey(song("same", "content://library/track")),
            nativeTrackFavoriteKey(song("same", "content://library/other")),
        )
        assertFalse(nativeTrackFavoriteKey(song("https://example.test?token=private")).orEmpty().contains("private"))
        assertNull(nativeTrackFavoriteKey(song("")))
    }
}
