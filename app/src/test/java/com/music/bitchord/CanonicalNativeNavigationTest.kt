package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.canonicalNativeBrowseId
import com.music.bitchord.ui.canonicalNativePageType
import com.music.bitchord.ui.canonicalNativeSections
import com.music.bitchord.ui.canonicalPreferredEditions
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalNativeNavigationTest {
    private val artist = CanonicalCatalogSearchRow("artist-id", CanonicalSearchKind.ARTIST, "ILLIT", null)
    private val group = CanonicalCatalogSearchRow("group-id", CanonicalSearchKind.RELEASE_GROUP,
        "SUPER REAL ME", "ILLIT", releaseKind = "EP")
    private val edition = CanonicalCatalogSearchRow("release-id", CanonicalSearchKind.RELEASE,
        "SUPER REAL ME", "ILLIT")

    @Test fun artistAndAlbumKeepNativeDetailTypesWithoutLegacyVideoIds() {
        assertEquals(BrowseType.ARTIST, canonicalNativePageType(artist.kind))
        assertEquals(BrowseType.ALBUM, canonicalNativePageType(group.kind))
        assertEquals(BrowseType.ALBUM, canonicalNativePageType(edition.kind))
        assertNull(canonicalNativePageType(CanonicalSearchKind.RECORDING))
    }

    @Test fun artistDiscographyUsesNativeShelvesWithIdentitySafeNavigation() {
        val sections = canonicalNativeSections(
            parentKind = CanonicalSearchKind.ARTIST,
            rows = listOf(group),
            heading = "Lançamentos",
        )
        assertEquals(1, sections.size)
        assertEquals("Lançamentos", sections.single().title)
        val card = sections.single().items.single()
        assertEquals(canonicalNativeBrowseId(group), card.browseId)
        assertNull(card.videoId)
        assertEquals("EP", card.subtitle)
    }

    @Test fun editionsStayDistinctAndNeverPretendToBePlayableTracks() {
        val alternate = edition.copy(musicBrainzId = "japan-release-id")
        val sections = canonicalNativeSections(
            parentKind = CanonicalSearchKind.RELEASE_GROUP,
            rows = listOf(edition, alternate),
            heading = "Edições",
        )
        assertEquals(2, sections.single().items.size)
        assertEquals(2, sections.single().items.map { it.browseId }.distinct().size)
        assertTrue(sections.single().items.all { it.videoId == null })
    }
    @Test fun preferredEditionAvoidsBootlegsAndRemixes() {
        val versions = listOf(
            MusicBrainzReleaseHit("remix", "SUPER REAL ME (sped up)", "JP",
                "2024-04-02", "ILLIT", status = "Official"),
            MusicBrainzReleaseHit("bootleg", "SUPER REAL ME", "XW",
                "2024-03-23", "ILLIT", status = "Bootleg"),
            MusicBrainzReleaseHit("main", "SUPER REAL ME", "KR",
                "2024-03-25", "ILLIT", status = "Official"),
            MusicBrainzReleaseHit("deluxe", "SUPER REAL ME (Deluxe)", "XW",
                "2024-04-01", "ILLIT", status = "Official"),
        )
        assertEquals("main", canonicalPreferredEditions(versions, "SUPER REAL ME").first().musicBrainzId)
    }

    @Test fun preferredEditionDoesNotInventIdentityOrMergeRealReleases() {
        val releases = listOf(
            MusicBrainzReleaseHit("first", "Original", null, null, "Artist", status = "Official"),
            MusicBrainzReleaseHit("second", "Original", null, null, "Artist", status = "Official"),
        )
        assertEquals(2, canonicalPreferredEditions(releases, "Original").size)
        assertEquals(setOf("first", "second"), canonicalPreferredEditions(releases, "Original").map { it.musicBrainzId }.toSet())
    }

    @Test fun unknownMusicBrainzGroupCannotDefaultToAnAlbumLabel() {
        assertTrue(!com.music.bitchord.ui.canonicalMayUseAlbumFallbackLabel("catalog:RELEASE_GROUP:unknown"))
        assertTrue(!com.music.bitchord.ui.canonicalMayUseAlbumFallbackLabel("catalog:RELEASE:edition"))
        assertTrue(com.music.bitchord.ui.canonicalMayUseAlbumFallbackLabel("local:album:1"))
    }

    @Test fun nativeArtistShelfPublishesArtworkForItsExactVerifiedGroup() {
        val verified = group.copy(
            musicBrainzId = "verified-group", releaseKind = "EP",
            artworkUri = "https://archive.org/verified-sleeve.jpg",
        )
        val result = canonicalNativeSections(
            parentKind = CanonicalSearchKind.ARTIST,
            rows = listOf(verified), heading = "Lançamentos",
        )
        assertEquals("https://archive.org/verified-sleeve.jpg",
            result.single().items.single().thumbnailUrl)
        assertNull(result.single().items.single().videoId)
    }

    @Test fun unknownMusicBrainzTypesMustNotEnterArtistAlbumsAndSinglesShelf() {
        val unclassified = group.copy(
            musicBrainzId = "unknown", title = "Right Now", releaseKind = "Other",
        )
        val known = group.copy(musicBrainzId = "known", title = "OMG", releaseKind = "Single")
        val sections = canonicalNativeSections(
            parentKind = CanonicalSearchKind.ARTIST,
            rows = listOf(unclassified, known), heading = "Lançamentos",
        )
        assertEquals(listOf("OMG"), sections.single().items.map { it.title })
    }

    @Test fun canonicalGroupTracklistDoesNotInsertEditionShelf() {
        val track = CanonicalCatalogSearchRow(
            musicBrainzId = "recording-1",
            kind = CanonicalSearchKind.RECORDING,
            title = "Magnetic",
            subtitle = "ILLIT",
            durationMs = 160_000L,
        )
        assertTrue(canonicalNativeSections(
            parentKind = CanonicalSearchKind.RELEASE_GROUP,
            rows = listOf(track),
            heading = "Edições",
        ).isEmpty())
        assertEquals(160_000L, track.durationMs)
    }

}
