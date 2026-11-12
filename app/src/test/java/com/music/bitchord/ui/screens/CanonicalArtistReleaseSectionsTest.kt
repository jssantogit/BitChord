package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun artistShelfLeavesUnclassifiedReleaseGroupsUnclassifiedRegardlessOfRecordingCount() {
        val rows = listOf(release("Plot Twist"), release("Butterfly Doors"),
            release("Say It"), release("unresolved"))
        val sections = canonicalArtistReleaseSections(
            releases = rows,
            evidence = emptyMap(),
            mapOf(
                "Plot Twist" to 4,
                "Butterfly Doors" to 1,
                "Say It" to 1,
            ),
        )
        assertTrue(sections.albums.isEmpty())
        assertTrue(sections.singlesAndEps.isEmpty())
        assertEquals(rows.map { it.id }, sections.unclassified.map { it.id })
        assertNull(sections.recentAlbum)
    }

    @Test fun unclassifiedUndatedReleasesPreserveArtistShelfOrderAfterDatedRows() {
        val rows = listOf(
            release("Zebra"), release("dated-old"), release("Alpha"),
            release("dated-new"), release("Middle"),
        )
        val proof = mapOf(
            "dated-old" to CanonicalArtistReleaseEvidence("dated-old", null, "2024-02-01"),
            "dated-new" to CanonicalArtistReleaseEvidence("dated-new", null, "2026-03-01"),
        )
        val sections = canonicalArtistReleaseSections(rows, proof)
        assertEquals(
            listOf("dated-new", "dated-old", "Zebra", "Alpha", "Middle"),
            sections.unclassified.map { it.id },
        )
        assertTrue(sections.albums.isEmpty())
        assertTrue(sections.singlesAndEps.isEmpty())
    }

    @Test fun officialReleaseGroupTypeOutranksProvisionalMembershipCount() {
        val rows = listOf(release("partial-album"), release("multi-track-ep"),
            release("official-single-multiple-recordings"), release("official-album-one-recording"),
            release("unsupported-type"))
        val proof = mapOf(
            "partial-album" to CanonicalArtistReleaseEvidence("partial-album", "Album", "2025-04-01"),
            "multi-track-ep" to CanonicalArtistReleaseEvidence("multi-track-ep", "EP", null),
            "official-single-multiple-recordings" to CanonicalArtistReleaseEvidence("official-single-multiple-recordings", "Single", null),
            "official-album-one-recording" to CanonicalArtistReleaseEvidence("official-album-one-recording", "Album", null),
            "unsupported-type" to CanonicalArtistReleaseEvidence("unsupported-type", "Other", null),
        )
        val sections = canonicalArtistReleaseSections(
            releases = rows,
            evidence = proof,
            mapOf("partial-album" to 1,
                "multi-track-ep" to 4, "official-single-multiple-recordings" to 5,
                "official-album-one-recording" to 1, "unsupported-type" to 8),
        )
        assertEquals(listOf("partial-album", "official-album-one-recording"), sections.albums.map { it.id })
        assertEquals(listOf("multi-track-ep", "official-single-multiple-recordings"), sections.singlesAndEps.map { it.id })
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
            mapOf(ep.id to 1)).recentRelease)
    }

    @Test fun nativeHeroCanUseARealReleaseDateEvenWithoutReleaseGroupType() {
        val dated = release("verified-edition")
        val undated = release("untagged")
        val result = canonicalArtistReleaseSections(listOf(undated, dated),
            mapOf(dated.id to CanonicalArtistReleaseEvidence(dated.id, null, "2026-08-18")),
            mapOf(undated.id to 3, dated.id to 1))
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

    @Test fun albumsAndSinglesOrderByVerifiedReleaseDateDescendingWithUnknownLast() {
        val rows = listOf(release("old-single"), release("unknown-album"),
            release("new-single"), release("old-album"), release("new-album"),
            release("unknown-single"))
        val evidence = mapOf(
            "old-single" to CanonicalArtistReleaseEvidence("old-single", "EP", "2022-06-14"),
            "new-single" to CanonicalArtistReleaseEvidence("new-single", "Single", "2026-08-19"),
            "unknown-single" to CanonicalArtistReleaseEvidence("unknown-single", "EP", null),
            "old-album" to CanonicalArtistReleaseEvidence("old-album", "Album", "2024-02-18"),
            "new-album" to CanonicalArtistReleaseEvidence("new-album", "Album", "2026-07-30"),
        )
        val result = canonicalArtistReleaseSections(rows, evidence,
            mapOf("unknown-album" to 6))
        assertEquals(listOf("new-album", "old-album"),
            result.albums.map { it.id })
        assertEquals(listOf("new-single", "old-single", "unknown-single"),
            result.singlesAndEps.map { it.id })
        assertEquals(listOf("unknown-album"), result.unclassified.map { it.id })
        assertEquals("new-single", result.recentRelease?.id)
    }

    @Test fun malformedReleaseDateIsNotPromotedAheadOfVerifiedDay() {
        val result = canonicalArtistReleaseSections(
            listOf(release("bad-date"), release("genuine"), release("future")),
            mapOf(
                "bad-date" to CanonicalArtistReleaseEvidence("bad-date", "Album", "2026-99-99"),
                "genuine" to CanonicalArtistReleaseEvidence("genuine", "Album", "2025-05-10"),
                "future" to CanonicalArtistReleaseEvidence("future", "Album", "2999-01-01"),
            ))
        assertEquals(listOf("future", "genuine", "bad-date"), result.albums.map { it.id })
        assertEquals("genuine", result.recentRelease?.id)
    }


    @Test fun localArtistCanFeatureOnlyUnambiguousExactMusicBrainzReleaseDates() {
        val local = listOf(release("Plot Twist"), release("Butterfly Doors"), release("Fan Remix"))
        val artists = listOf(MusicBrainzArtistHit(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "AtHeart", null, "KR",
        ))
        val groups = listOf(
            MusicBrainzReleaseGroupHit("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "Plot Twist", "Album", "2025-08-13", "AtHeart"),
            MusicBrainzReleaseGroupHit("cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "Butterfly Doors", "Single", "2026-05-12", "AtHeart"),
            MusicBrainzReleaseGroupHit("dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                "Fan Remix", "Album", "2026-09-02", "Various Artists", secondaryTypes = "Compilation"),
        )
        val matched = canonicalLocalArtistExactReleaseDateEvidence(
            "AtHeart", local, artists, groups,
        )
        assertEquals(2, matched.size)
        assertEquals("2026-05-12", matched["Butterfly Doors"]?.firstReleaseDate)
        assertEquals("Butterfly Doors", canonicalArtistReleaseSections(
            local, matched, mapOf("Plot Twist" to 4,
                "Butterfly Doors" to 1, "Fan Remix" to 4)).recentRelease?.id)
    }

    @Test fun artistNameCollisionOrAmbiguousAlbumTitleNeverInventsRecentRelease() {
        val artistA = MusicBrainzArtistHit(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "AtHeart", null, null)
        val artistB = MusicBrainzArtistHit(
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "AtHeart", null, null)
        val match = MusicBrainzReleaseGroupHit(
            "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            "Plot Twist", "Album", "2025-08-13", "AtHeart",
        )
        val local = listOf(release("Plot Twist"))
        assertEquals(emptyMap<String, CanonicalArtistReleaseEvidence>(),
            canonicalLocalArtistExactReleaseDateEvidence("AtHeart", local,
                listOf(artistA, artistB), listOf(match)))
        assertEquals(emptyMap<String, CanonicalArtistReleaseEvidence>(),
            canonicalLocalArtistExactReleaseDateEvidence("AtHeart", local,
                listOf(artistA), listOf(match, match.copy(
                    musicBrainzId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))))
        assertEquals(emptyMap<String, CanonicalArtistReleaseEvidence>(),
            canonicalLocalArtistExactReleaseDateEvidence("AtHeart", local,
                listOf(artistA), listOf(match.copy(firstReleaseDate = "2025"))))
    }


    @Test fun duplicateLocalTitlesCannotBothBorrowOneMusicBrainzGroupClassification() {
        val local = listOf(
            ReleaseEntity("edition-one", "Plot Twist", "AtHeart", null),
            ReleaseEntity("edition-two", "  plot   twist  ", "AtHeart", null),
            ReleaseEntity("unique-single", "Say It", "AtHeart", null),
        )
        val artists = listOf(MusicBrainzArtistHit(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "AtHeart", null, "KR",
        ))
        val groups = listOf(
            MusicBrainzReleaseGroupHit(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "Plot Twist", "Album", "2025-08-13", "AtHeart",
            ),
            MusicBrainzReleaseGroupHit(
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "Say It", "Single", "2026-06-25", "AtHeart",
            ),
        )
        val proof = canonicalLocalArtistExactReleaseDateEvidence(
            "AtHeart", local, artists, groups,
        )
        // A shared title cannot prove which of two local editions owns the
        // online group; never manufacture two Album/RecentRelease claims.
        assertEquals(setOf("unique-single"), proof.keys)
        assertEquals("Single", proof["unique-single"]?.primaryType)
        assertEquals("2026-06-25", proof["unique-single"]?.firstReleaseDate)
    }

    @Test fun diskProofLookupRejectsWhitespaceEquivalentDuplicateTitles() {
        val local = listOf(
            ReleaseEntity("first", "Plot Twist", "AtHeart", null),
            ReleaseEntity("second", "  plot   twist  ", "AtHeart", null),
        )
        // Both strings hash to the exact same canonical cache key. This
        // must NOT allow one previously dated group to populate both IDs.
        val key = canonicalRecentArtistProofCacheKey("AtHeart", "Plot Twist")
        assertEquals(key, canonicalRecentArtistProofCacheKey(
            "AtHeart", "  plot   twist  ",
        ))
        assertEquals(emptyMap<String, CanonicalArtistReleaseEvidence>(),
            canonicalCachedRecentArtistProofs(
                "AtHeart", local, mapOf(key to "Album|2025-08-13")::get,
            ))
    }

    @Test fun incompleteArtistSearchCannotClaimThatAnExactNameIsGloballyUnique() {
        val matched = MusicBrainzArtistHit(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "AtHeart", null, "KR",
        )
        val unrelated = MusicBrainzArtistHit(
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "AtHeart covers", null, "KR",
        )
        // Nineteen other raw hits may be filtered or have different names;
        // the server still says later pages exist. A namesake could be there.
        val incomplete = MusicBrainzCatalogPage(
            items = listOf(matched), total = 45, offset = 0, consumedCount = 20,
        )
        assertNull(canonicalUniqueExactArtistFromCompleteSearch("AtHeart", incomplete))
        assertNull(canonicalUniqueExactArtistFromCompleteSearch(
            "AtHeart", incomplete.copy(offset = 20, total = 40),
        ))
        val complete = MusicBrainzCatalogPage(
            items = listOf(matched, unrelated), total = 2, offset = 0, consumedCount = 2,
        )
        assertEquals(matched, canonicalUniqueExactArtistFromCompleteSearch(
            "  atheart  ", complete,
        ))
        assertNull(canonicalUniqueExactArtistFromCompleteSearch(
            "AtHeart", complete.copy(
                items = listOf(matched, matched.copy(
                    musicBrainzId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                )),
            ),
        ))
    }

    @Test fun verifiedRecentLibraryReleaseSurvivesRestartAndChangedLocalIdsWithoutInternet() {
        val online = ReleaseEntity("old-scan-id", "Say It", "AtHeart", null)
        val offline = online.copy(id = "new-scan-id")
        val key = canonicalRecentArtistProofCacheKey("AtHeart", online.title)
        val stored = mapOf(key to "Single|2026-06-25")
        val proof = canonicalCachedRecentArtistProofs("AtHeart", listOf(offline), stored::get)
        assertEquals("2026-06-25", proof[offline.id]?.firstReleaseDate)
        assertEquals("Single", proof[offline.id]?.primaryType)
        assertEquals(offline.id, canonicalArtistReleaseSections(
            listOf(offline), proof, mapOf(offline.id to 1),
        ).recentRelease?.id)
    }

    @Test fun cachedRecentProofRejectsAmbiguousTitlesWrongArtistMalformedAndFutureDates() {
        val release = ReleaseEntity("local", "Say It", "AtHeart", null)
        val valid = canonicalRecentArtistProofCacheKey("AtHeart", release.title)
        val storage = mapOf(valid to "Single|2026-06-25")
        assertEquals(emptyMap<String, CanonicalArtistReleaseEvidence>(),
            canonicalCachedRecentArtistProofs("Other Artist", listOf(release), storage::get))
        assertEquals(emptyMap<String, CanonicalArtistReleaseEvidence>(),
            canonicalCachedRecentArtistProofs("AtHeart", listOf(
                release, release.copy(id = "ambiguous"),
            ), storage::get))
        assertEquals(emptyMap<String, CanonicalArtistReleaseEvidence>(),
            canonicalCachedRecentArtistProofs("AtHeart", listOf(release)) { "Album|2999-01-01" })
        assertEquals(emptyMap<String, CanonicalArtistReleaseEvidence>(),
            canonicalCachedRecentArtistProofs("AtHeart", listOf(release)) { "Other|2026-06-25" })
    }

    @Test fun recentLibraryExternalDateLookupDoesNotRestartForArtworkOnlyChanges() {
        val releases = listOf(
            ReleaseEntity("local-1", "Plot Twist", "AtHeart", null),
            ReleaseEntity("local-2", "Butterfly Doors", "AtHeart", null),
        )
        val key = canonicalLocalArtistRecentLookupKey("AtHeart", releases)
        // A Coil/Room artwork refresh may emit an updated artist state while
        // MusicBrainz is still paging. It must not cancel the date lookup.
        assertEquals(key, canonicalLocalArtistRecentLookupKey("AtHeart",
            releases.map { it.copy(artworkUri = "content://media/cover") }))
        assertEquals(key, canonicalLocalArtistRecentLookupKey("AtHeart", releases.reversed()))
        assertEquals(false, key == canonicalLocalArtistRecentLookupKey("AtHeart",
            releases + ReleaseEntity("local-3", "Say It", "AtHeart", null)))
        // A rescan may replace a local release ID while the artist/title
        // remains unchanged. Its old external-date cache must not be reused.
        assertEquals(false, key == canonicalLocalArtistRecentLookupKey("AtHeart",
            releases.map { if (it.id == "local-1") it.copy(id = "new-scan-id") else it }))
        assertEquals(false, key == canonicalLocalArtistRecentLookupKey("Other Artist", releases))
        assertEquals(false, key == canonicalLocalArtistRecentLookupKey("AtHeart",
            releases.map { if (it.id == "local-1") it.copy(title = "Another release") else it }))
    }

}
