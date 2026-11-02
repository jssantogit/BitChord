package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.ReleaseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalArtistReleaseSectionsTest {
    private fun release(id: String) = ReleaseEntity(id, id, "aespa", null)

    @Test fun onlyVerifiedAlbumAndSingleEpTypesCreateSections() {
        val rows = listOf(release("album"), release("single"), release("unknown"))
        val evidence = mapOf(
            "album" to CanonicalArtistReleaseEvidence("album", "Album", "2024-07-01"),
            "single" to CanonicalArtistReleaseEvidence("single", "Single", "2025-07-01"),
        )
        val sections = canonicalArtistReleaseSections(rows, evidence)
        assertEquals(listOf("album"), sections.albums.map { it.id })
        assertEquals(listOf("single"), sections.singlesAndEps.map { it.id })
        assertEquals(listOf("unknown"), sections.unclassified.map { it.id })
        assertEquals("album", sections.recentAlbum?.id)
    }

    @Test fun recentAlbumRequiresTrustedReleaseDate() {
        val releases = listOf(release("newer"), release("older"))
        val proof = mapOf(
            "newer" to CanonicalArtistReleaseEvidence("newer", "Album", null),
            "older" to CanonicalArtistReleaseEvidence("older", "Album", "2023-04-11"),
        )
        assertEquals("older", canonicalArtistReleaseSections(releases, proof).recentAlbum?.id)
        assertNull(canonicalArtistReleaseSections(releases,
            mapOf("newer" to CanonicalArtistReleaseEvidence("newer", "Album", "2025"))).recentAlbum)
    }
    @Test fun openedReleaseIncludesItsEntireVisibleTrackMembershipNotOnlySelectedArtistRows() {
        val allVisible = listOf("artist-song", "featured-collaboration", "unrelated")
        val membership = setOf("artist-song", "featured-collaboration")
        val artistRows = listOf("artist-song")
        assertEquals(
            listOf("artist-song", "featured-collaboration"),
            canonicalArtistReleaseTrackIds(allVisible, membership),
        )
        assertEquals(
            listOf("artist-song"),
            canonicalArtistReleaseTrackIds(artistRows, membership),
        )
    }

    @Test fun artistHeaderMustNotUseAnAlbumCoverAsLargeArtistPhoto() {
        assertNull(canonicalArtistHeroPhotoUrl(null, "content://media/album-cover"))
        assertEquals(
            "https://images.example/verified-artist.jpg",
            canonicalArtistHeroPhotoUrl("https://images.example/verified-artist.jpg", "content://media/album-cover"),
        )
    }

    @Test fun artistSearchIsHiddenUnlessInvokedOrHoldingQuery() {
        assertEquals(false, shouldShowNativeArtistSearch(false, ""))
        assertEquals(true, shouldShowNativeArtistSearch(true, ""))
        assertEquals(true, shouldShowNativeArtistSearch(false, "aespa"))
    }

}
