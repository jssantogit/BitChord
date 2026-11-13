package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Native library album routes must come from an exact release/asset link. */
class CanonicalLibraryAlbumNavigationTest {
    @Test fun serverOwnedReleaseOpensWithoutMusicBrainzNetworkBrowse() {
        assertEquals("edition-navidrome", uniqueSourceOwnedAlbumReleaseId(
            linkedReleaseIds = listOf("edition-navidrome", "edition-other"),
            sourceReleaseIds = setOf("edition-navidrome"),
        ))
    }

    @Test fun localMediaStoreUsesTheSameSourceOwnedRoute() {
        assertEquals("edition-local", uniqueSourceOwnedAlbumReleaseId(
            linkedReleaseIds = listOf("edition-local"),
            sourceReleaseIds = setOf("edition-local"),
        ))
    }

    @Test fun unrelatedOrAmbiguousAlbumCannotBeGuessedFromTitle() {
        assertNull(uniqueSourceOwnedAlbumReleaseId(
            linkedReleaseIds = listOf("edition-a"),
            sourceReleaseIds = setOf("edition-b"),
        ))
        assertNull(uniqueSourceOwnedAlbumReleaseId(
            linkedReleaseIds = listOf("edition-a", "edition-b"),
            sourceReleaseIds = setOf("edition-a", "edition-b"),
        ))
    }

    @Test fun currentNativeAlbumIsExplicitEvidenceEvenWithOtherEditions() {
        assertEquals("edition-b", uniqueSourceOwnedAlbumReleaseId(
            linkedReleaseIds = listOf("edition-a", "edition-b"),
            sourceReleaseIds = setOf("edition-a", "edition-b"),
            preferredReleaseId = "edition-b",
        ))
        assertNull(uniqueSourceOwnedAlbumReleaseId(
            linkedReleaseIds = listOf("edition-a"),
            sourceReleaseIds = setOf("edition-a"),
            preferredReleaseId = "edition-b",
        ))
    }
}
