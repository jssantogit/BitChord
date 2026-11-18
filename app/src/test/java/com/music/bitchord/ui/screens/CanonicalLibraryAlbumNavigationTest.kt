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

    @Test fun playerRequiresAProvenAlbumOnTheSameMediaSource() {
        val evidence = listOf(
            SourceOwnedAlbumEvidence("local-edition", "Local", "Artist", "local-mediastore"),
            SourceOwnedAlbumEvidence("server-edition", "Server", "Artist", "opensubsonic:one"),
        )
        val album = verifiedSourceOwnedPlayerAlbum(
            "recording-id", "opensubsonic:one",
            listOf("local-edition", "server-edition"), evidence,
        )
        assertEquals("server-edition", album?.releaseId)
        assertEquals("opensubsonic:one", album?.providerKey)
        assertNull(verifiedSourceOwnedPlayerAlbum(
            "recording-id", "opensubsonic:another",
            listOf("server-edition"), evidence,
        ))
        assertNull(verifiedSourceOwnedPlayerAlbum(
            "", "opensubsonic:one", listOf("server-edition"), evidence,
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
