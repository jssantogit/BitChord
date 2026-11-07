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

    @Test fun artistShelfSeparatesAvailableLocalAlbumAndSinglesWhenReleaseGroupTypeIsMissing() {
        // Browse-only fallback: local album tags provide a release membership
        // even when a MusicBrainz ReleaseGroup classification was not indexed.
        val rows = listOf(release("Plot Twist"), release("Butterfly Doors"),
            release("Say It"), release("unresolved"))
        val sections = canonicalArtistReleaseSections(
            releases = rows,
            evidence = emptyMap(),
            recordingCountsByRelease = mapOf(
                "Plot Twist" to 4,
                "Butterfly Doors" to 1,
                "Say It" to 1,
            ),
        )
        assertEquals(listOf("Plot Twist"), sections.albums.map { it.id })
        assertEquals(listOf("Butterfly Doors", "Say It"),
            sections.singlesAndEps.map { it.id })
        assertEquals(listOf("unresolved"), sections.unclassified.map { it.id })
        assertNull(sections.recentAlbum)
    }

    @Test fun officialReleaseGroupTypeOutranksProvisionalMembershipCount() {
        val rows = listOf(release("partial-album"), release("multi-track-ep"),
            release("unsupported-type"))
        val proof = mapOf(
            "partial-album" to CanonicalArtistReleaseEvidence("partial-album", "Album", "2025-04-01"),
            "multi-track-ep" to CanonicalArtistReleaseEvidence("multi-track-ep", "EP", null),
            "unsupported-type" to CanonicalArtistReleaseEvidence("unsupported-type", "Other", null),
        )
        val sections = canonicalArtistReleaseSections(
            releases = rows,
            evidence = proof,
            recordingCountsByRelease = mapOf("partial-album" to 1,
                "multi-track-ep" to 4, "unsupported-type" to 8),
        )
        assertEquals(listOf("partial-album"), sections.albums.map { it.id })
        assertEquals(listOf("multi-track-ep"), sections.singlesAndEps.map { it.id })
        assertEquals(listOf("unsupported-type"), sections.unclassified.map { it.id })
        assertEquals("partial-album", sections.recentAlbum?.id)
    }

    @Test fun artistWithoutPortraitUsesCompactNativeHeaderInsteadOfHugeBlankCanvas() {
        assertEquals(true, shouldCompactNativeArtistHero(null))
        assertEquals(true, shouldCompactNativeArtistHero(" "))
        assertEquals(false, shouldCompactNativeArtistHero("https://example.com/portrait.jpg"))
    }


    @Test fun latestSingleCanBeFeaturedWithoutAnAlbum() {
        val album = release("older-album")
        val single = release("latest-single")
        val ep = release("older-ep")
        val sections = canonicalArtistReleaseSections(listOf(album, single, ep),
            mapOf(
                album.id to CanonicalArtistReleaseEvidence(album.id, "Album", "2024-03-01"),
                single.id to CanonicalArtistReleaseEvidence(single.id, "Single", "2026-08-20"),
                ep.id to CanonicalArtistReleaseEvidence(ep.id, "EP", "2025-05-04"),
            ),
        )
        assertEquals("older-album", sections.recentAlbum?.id)
        assertEquals("latest-single", sections.recentRelease?.id)
    }

    @Test fun onlyVerifiedDatesAndTypesMayBecomeFeaturedReleases() {
        val ep = release("date-less-ep")
        val invalid = release("invalid")
        val typed = release("dated-ep")
        val sections = canonicalArtistReleaseSections(listOf(ep, invalid, typed),
            mapOf(
                ep.id to CanonicalArtistReleaseEvidence(ep.id, "EP", null),
                invalid.id to CanonicalArtistReleaseEvidence(invalid.id, "Other", "2026-07-02"),
                typed.id to CanonicalArtistReleaseEvidence(typed.id, "EP", "2026-02-15"),
            ),
        )
        assertNull(sections.recentAlbum)
        assertEquals("dated-ep", sections.recentRelease?.id)
        assertNull(canonicalArtistReleaseSections(listOf(ep), emptyMap(),
            recordingCountsByRelease = mapOf(ep.id to 1)).recentRelease)
    }

    @Test fun nativeHeroCanUseARealReleaseDateEvenWithoutReleaseGroupType() {
        val dated = release("verified-edition")
        val undated = release("untagged")
        val result = canonicalArtistReleaseSections(listOf(undated, dated),
            mapOf(dated.id to CanonicalArtistReleaseEvidence(dated.id, null, "2026-08-18")),
            recordingCountsByRelease = mapOf(undated.id to 3, dated.id to 1))
        assertEquals("verified-edition", result.recentRelease?.id)
        assertNull(result.recentAlbum)
    }

    @Test fun artistReleaseDateShowsLocalizedReadableDay() {
        assertEquals("18 de set. de 2026", canonicalArtistReleaseDateLabel("2026-09-18"))
        assertEquals("2026", canonicalArtistReleaseDateLabel("2026"))
        assertEquals(null, canonicalArtistReleaseDateLabel("20xx-09-18"))
    }

    @Test fun trustedEditionDateEnablesLibraryHeroWithoutGroupClassification() {
        val release = release("exact-release")
        val proof = canonicalArtistReleaseEvidenceWithTrustedDate(
            release.id, null, "2026-09-18")
        assertEquals("2026-09-18", proof.firstReleaseDate)
        assertNull(proof.primaryType)
        assertEquals("exact-release", canonicalArtistReleaseSections(
            listOf(release), mapOf(release.id to proof)).recentRelease?.id)
    }

    @Test fun groupFirstDateOutranksEditionDateAndEmptyMetadataStaysUnknown() {
        val group = CanonicalArtistReleaseEvidence("release", "Single", "2025-04-20")
        val proof = canonicalArtistReleaseEvidenceWithTrustedDate(
            "release", group, "2026-01-31")
        assertEquals("2025-04-20", proof.firstReleaseDate)
        assertNull(canonicalArtistReleaseEvidenceWithTrustedDate(
            "release", null, null).firstReleaseDate)
    }
}
