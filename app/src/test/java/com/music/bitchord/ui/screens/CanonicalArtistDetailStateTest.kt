package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.ReleaseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract before extracting the canonical artist detail data source. */
class CanonicalArtistDetailStateTest {
    @Test fun onlyVerifiedRecordingToReleaseMembershipEntersArtistDetails() {
        val linked = ReleaseEntity("linked-release", "同名", "aespa", null)
        val homonym = ReleaseEntity("other-release", "同名", "aespa", null)
        val releases = listOf(homonym, linked, linked.copy(artworkUri = "later-cover"))

        assertEquals(
            listOf("linked-release"),
            canonicalArtistVerifiedReleases(releases, setOf(linked.id)).map { it.id },
        )
    }

    @Test fun scopeDuplicatesNeverCauseDuplicateAlbumsAndOrderIsStable() {
        val first = ReleaseEntity("release-b", "B", "artist", null)
        val second = ReleaseEntity("release-a", "A", "artist", null)
        assertEquals(
            listOf("release-b", "release-a"),
            canonicalArtistVerifiedReleases(
                listOf(first, second, first), setOf("release-a", "release-b"),
            ).map { it.id },
        )
        assertTrue(canonicalArtistVerifiedReleases(
            listOf(first, second), emptySet(),
        ).isEmpty())
    }
    @Test fun nestedAlbumUsesAvailableSourceUnionWithoutDuplicateRecordings() {
        assertEquals(
            listOf("recording-b", "recording-a", "recording-c"),
            canonicalArtistNestedRecordingOrder(
                listOf(
                    listOf("recording-b", "recording-a"),
                    listOf("recording-a", "recording-c"),
                ),
            ),
        )
        assertTrue(canonicalArtistNestedRecordingOrder(emptyList()).isEmpty())
    }

}
