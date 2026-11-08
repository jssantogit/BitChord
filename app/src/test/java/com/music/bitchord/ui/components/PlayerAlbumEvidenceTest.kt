package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerAlbumEvidenceTest {
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
}
