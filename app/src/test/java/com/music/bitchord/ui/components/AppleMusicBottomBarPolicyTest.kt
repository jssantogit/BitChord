package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import com.music.bitchord.data.model.Song
import org.junit.Test

/** The disabled Radio slot must never steal Library/Search route indices. */
class AppleMusicBottomBarPolicyTest {
    @Test
    fun miniplayerUsesQueueIdentityRatherThanAllHydratedSongMetadata() {
        val initial = Song(
            videoId = "catalog:recording:one",
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            queueEntryId = "queue-entry-one",
        )
        val hydrated = initial.copy(
            durationText = "3:41",
            thumbnailUrl = "https://example.invalid/artwork.jpg",
            sourceQuality = "LOSSLESS",
        )
        assertEquals(appleMiniPlayerContentKey(initial), appleMiniPlayerContentKey(hydrated))
        assertNotEquals(
            appleMiniPlayerContentKey(initial),
            appleMiniPlayerContentKey(initial.copy(queueEntryId = "queue-entry-two")),
        )
    }

    @Test
    fun miniplayerKeySupportsLegacyAndLocalEntriesWithoutQueueIdentity() {
        val first = Song(videoId = "song-one", title = "First", artist = "A", thumbnailUrl = null)
        val second = first.copy(videoId = "song-two")
        assertNotEquals(appleMiniPlayerContentKey(first), appleMiniPlayerContentKey(second))
        assertEquals(appleMiniPlayerContentKey(first), appleMiniPlayerContentKey(first.copy(artistId = "artist-new")))
        val local = first.copy(videoId = "", localUri = "content://media/external/audio/1")
        assertNotEquals(appleMiniPlayerContentKey(first), appleMiniPlayerContentKey(local))
        assertEquals("local:content://media/external/audio/1", appleMiniPlayerContentKey(local))
    }

    @Test
    fun fiveVisualSlotsOnlyExposeFourExistingDestinations() {
        assertEquals(
            listOf(0, 1, null, 2, 3),
            (0..4).map(::appleMusicDestinationForVisualIndex),
        )
    }

    @Test
    fun radioIsDisabledNotMappedToLibraryOrSearch() {
        assertNull(appleMusicDestinationForVisualIndex(2))
        assertNull(appleMusicDestinationForVisualIndex(-1))
        assertNull(appleMusicDestinationForVisualIndex(5))
    }

    @Test
    fun libraryAndSearchSelectionAppearAfterRadio() {
        assertEquals(
            listOf(0, 1, 3, 4),
            (0..3).map(::appleMusicVisualIndexForDestination),
        )
        for (destination in 0..3) {
            assertEquals(
                destination,
                appleMusicDestinationForVisualIndex(
                    appleMusicVisualIndexForDestination(destination),
                ),
            )
        }
    }
}
