package com.music.bitchord.ui.screens

import com.music.bitchord.ui.canonicalProgressiveSearchPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSearchProgressiveTest {
    private val artistId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val recordingId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test fun exactOnlineArtistSupersedesOnlyItsOwnCachedIdentity() {
        val cached = listOf(
            CanonicalCatalogSearchRow(artistId, CanonicalSearchKind.ARTIST, "Old cached title",
                fromOfflineCache = true),
            CanonicalCatalogSearchRow(recordingId, CanonicalSearchKind.RECORDING, "Song",
                fromOfflineCache = true),
        )
        val fresh = listOf(
            CanonicalCatalogSearchRow(artistId, CanonicalSearchKind.ARTIST, "Updated title"),
        )
        val rows = canonicalProgressiveSearchPreview(cached, fresh)
        assertEquals(listOf(artistId, recordingId), rows.map { it.musicBrainzId })
        assertEquals("Updated title", rows.first().title)
        assertTrue(rows.last().fromOfflineCache)
    }

    @Test fun distinctEntityKindsWithSameMbidRemainDistinct() {
        val cached = listOf(
            CanonicalCatalogSearchRow(artistId, CanonicalSearchKind.RELEASE_GROUP, "Album"),
        )
        val fresh = listOf(
            CanonicalCatalogSearchRow(artistId, CanonicalSearchKind.ARTIST, "Artist"),
        )
        assertEquals(2, canonicalProgressiveSearchPreview(cached, fresh).size)
    }
}
