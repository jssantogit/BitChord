package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.QueueTier
import org.junit.Test

class PlayerAlbumEvidenceTest {
    @Test fun multipleEditionsOfOneVerifiedReleaseGroupCanOpenTheirAlbum() {
        val links = listOf(
            "recording-a" to "group-1",
            "recording-a" to "group-1",
            "recording-b" to "group-1",
        )
        assertEquals("group-1", sharedVerifiedReleaseGroupId(listOf("recording-a"), links))
        assertEquals("group-1",
            sharedVerifiedReleaseGroupId(listOf("recording-a", "recording-b"), links))
        assertNull(sharedVerifiedReleaseGroupId(listOf("recording-a"),
            links + ("recording-a" to "group-2")))
        assertNull(sharedVerifiedReleaseGroupId(listOf("recording-a", "recording-missing"), links))
    }

    @Test fun albumNavigationUsesOnlyAUniqueVerifiedReleaseNotAnAmbiguousEdition() {
        val one = listOf(Triple("recording-a", "edition-1", "Album"))
        assertEquals("edition-1", sharedVerifiedReleaseId(listOf("recording-a"), one))
        val ambiguous = one + Triple("recording-a", "edition-2", "Album")
        assertNull(sharedVerifiedReleaseId(listOf("recording-a"), ambiguous))
        assertNull(sharedVerifiedReleaseId(listOf("recording-a", "missing"), one))
        val common = ambiguous + Triple("recording-b", "edition-1", "Album")
        assertEquals("edition-1",
            sharedVerifiedReleaseId(listOf("recording-a", "recording-b"), common))
        assertEquals("edition-2",
            sharedVerifiedReleaseId(listOf("recording-a"), ambiguous, "edition-2"))
    }

    @Test fun sharedExactReleaseNameAcrossSongs() {
        val rows = listOf(
            Triple("recording-a", "release-1", "ARIRANG"),
            Triple("recording-b", "release-1", "ARIRANG"),
            Triple("recording-a", "release-2", "Another Edition"),
        )
        assertEquals(
            "ARIRANG",
            sharedVerifiedReleaseTitle(listOf("recording-a", "recording-b"), rows),
        )
    }

    @Test fun noArtistBasedOrArbitraryReleaseGuessing() {
        val rows = listOf(
            Triple("recording-a", "release-1", "Album A"),
            Triple("recording-a", "release-2", "Album B"),
        )
        assertNull(sharedVerifiedReleaseTitle(listOf("recording-a"), rows))
        assertNull(sharedVerifiedReleaseTitle(listOf("recording-a", "recording-b"), rows))
        assertNull(sharedVerifiedReleaseTitle(listOf(""), rows))
    }

    @Test fun explicitLinkedReleaseDisambiguatesEditions() {
        val rows = listOf(
            Triple("recording-a", "release-1", "Album"),
            Triple("recording-a", "release-2", "Album — Special"),
        )
        assertEquals(
            "Album — Special",
            sharedVerifiedReleaseTitle(listOf("recording-a"), rows, "release-2"),
        )
    }

    @Test fun identicalTitleAcrossMultipleVerifiedEditionsIsDisplaySafe() {
        assertEquals(
            "Album",
            sharedVerifiedReleaseTitle(
                listOf("recording-a"),
                listOf(
                    Triple("recording-a", "release-1", "Album"),
                    Triple("recording-a", "release-2", "Album"),
                ),
            ),
        )
    }

    private fun track(
        title: String,
        artist: String = "SAY MY NAME",
        art: String? = "https://example.invalid/our-vibe.jpg",
        tier: QueueTier = QueueTier.CONTEXT,
        album: String? = null,
        albumId: String? = null,
    ) = Song(
        videoId = title, title = title, artist = artist, thumbnailUrl = art,
        queueTier = tier, albumName = album, albumId = albumId,
    )

    @Test fun albumProvenanceFromCurrentTrackSurvivesIncompleteUpcomingIdentifiers() {
        val current = track("Bad Idea")
        val future = listOf(track("UFO"), track("Delulu Solulu"))
        assertEquals("&Our Vibe", contextualAlbumFromCurrent(current, future, "&Our Vibe"))
    }

    @Test fun autoplayOrManualQueueDoesNotInheritCurrentAlbum() {
        val current = track("Bad Idea")
        assertNull(contextualAlbumFromCurrent(current, listOf(track("Radio", tier = QueueTier.AUTOPLAY)), "&Our Vibe"))
        assertNull(contextualAlbumFromCurrent(current, listOf(track("User choice", tier = QueueTier.USER_QUEUE)), "&Our Vibe"))
    }

    @Test fun unrelatedArtworkArtistOrExplicitAlbumBlocksFallback() {
        val current = track("Bad Idea", albumId = "our-vibe")
        assertNull(contextualAlbumFromCurrent(current, listOf(track("Different", art = "different")), "&Our Vibe"))
        assertNull(contextualAlbumFromCurrent(current, listOf(track("Different", artist = "Different artist")), "&Our Vibe"))
        assertNull(contextualAlbumFromCurrent(current, listOf(track("Different", albumId = "other-id")), "&Our Vibe"))
        assertNull(contextualAlbumFromCurrent(current, listOf(track("Different", album = "Other release")), "&Our Vibe"))
    }

    @Test fun fallbackRequiresActualAlbumEvidence() {
        assertNull(contextualAlbumFromCurrent(track("Bad Idea"), listOf(track("UFO")), null))
        assertNull(contextualAlbumFromCurrent(track("Bad Idea", art = null), listOf(track("UFO", art = null)), "&Our Vibe"))
    }

    @Test fun playerMetadataCanEnrichMatchingQueueEntryWithoutOverwritingItsTier() {
        val queued = track("Bad Idea", album = null)
        val displayed = track("Bad Idea", album = "&Our Vibe")
        assertEquals("&Our Vibe", effectiveQueueAlbumSeed(queued, displayed).albumName)
        assertEquals(QueueTier.CONTEXT, effectiveQueueAlbumSeed(queued, displayed).queueTier)
        assertEquals(null, effectiveQueueAlbumSeed(queued, track("Another Song", album = "Wrong")).albumName)
    }
}
