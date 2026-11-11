package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import org.junit.Assert.*
import org.junit.Test

class DiscogsEditionMatcherTest {
    private val mbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val linked = DiscogsLink(DiscogsKind.RELEASE, 12001)
    private val canon = CanonicalEditionEvidence(
        musicBrainzReleaseId = mbid, title = "예쁜 앨범",
        creditedArtist = "아티스트", status = "Official",
        barcodes = listOf("012345678905"),
        trackTitles = listOf("트랙 一", "트랙 二"),
    )
    private val record = DiscogsRecord(
        link = linked, title = "예쁜 앨범", artistNames = listOf("아티스트"),
        country = "KR", year = 2024,
        labels = listOf("Label" to "KR-11"), barcodes = listOf("0 12345 67890 5"),
        formats = listOf("CD", "Album"),
        tracklist = listOf("1" to "트랙 一", "2" to "트랙 二"),
        unofficial = false,
    )

    @Test fun identicalUpcTracksCannotVerifyDifferentCatalogNumberOnSameLabel() {
        // Two physical package variants can share their EAN and tracks.
        // Source-owned exact label + catalog-number pairs must reach the
        // edition matcher; no fuzzy label aliases or catalog inference.
        val canonical = canon.copy(
            editionLabelCatalogPairs = listOf("BELIFT LAB" to "BEL-001"),
            editionFormats = listOf("CD"),
        )
        val matching = record.copy(labels = listOf("BELIFT LAB" to "BEL-001"))
        val different = record.copy(labels = listOf("Belift Lab" to "BEL-002"))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(canonical, linked, matching))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assessUnlinked(canonical, matching))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canonical, linked, different,
                explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assessUnlinked(canonical, different))
        // Unknown labels, absent catalog numbers and producer aliases are
        // inconclusive; none is proof that two different numbers disagree.
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(canonical, linked,
                record.copy(labels = listOf("Different label" to "BEL-002"))))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(canonical, linked,
                record.copy(labels = listOf("BELIFT LAB" to ""))))
        // Opposing numbers on a shared label remain unresolved even if a
        // third, unrelated publisher and catalog entry coincidentally match.
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canonical.copy(
                editionLabelCatalogPairs = listOf(
                    "BELIFT LAB" to "BEL-001", "Other Label" to "XYZ"),
            ), linked, record.copy(labels = listOf(
                "BELIFT LAB" to "BEL-002", "Other Label" to "XYZ",
            ))))
    }

    @Test fun confirmedEditionNeedsAnExactDiscogsReleaseLinkAndIndependentCorroboration() {
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(canon, linked, record))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canon, DiscogsLink(DiscogsKind.RELEASE, 999), record))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canon, DiscogsLink(DiscogsKind.MASTER, 12001), record))
    }

    @Test fun realEasyCrazyHotCarbonBlackDirectLinkCorroboratesPunctuationWithoutMergingVinylVariants() {
        // Public control: MusicBrainz Release e6de9634 and Discogs Release
        // 35021231 have a typed direct URL relationship. SOURCE MUSIC's
        // Carbon Black 12-inch has barcode 198704525124; the Silver White
        // edition is another Discogs Release (35025845), with barcode
        // 198704525131. One punctuation difference in fifteen tracks.
        val mbEdition = canon.copy(
            musicBrainzReleaseId = "e6de9634-eb96-4b54-9568-755a99ec312b",
            title = "EASY CRAZY HOT", creditedArtist = "LE SSERAFIM",
            creditedArtistNames = listOf("LE SSERAFIM"),
            country = "US", releaseYear = 2025, barcodes = listOf("198704525124"),
            trackTitles = listOf("Good Bones", "EASY", "Swan Song", "Smart",
                "We got so much", "Chasing Lightning", "CRAZY", "Pierrot",
                "1‐800‐hot‐n‐fun", "미치지 못하는 이유", "Born Fire",
                "HOT", "Come Over", "Ash", "So Cynical (Badum)"),
        )
        val linkedBlack = DiscogsLink(DiscogsKind.RELEASE, 35021231)
        val carbonBlack = record.copy(
            link = linkedBlack,
            title = "Easy-Crazy-Hot", artistNames = listOf("LE SSERAFIM"),
            country = "United States", year = 2025,
            barcodes = listOf("198704525124"),
            labels = listOf("SOURCE MUSIC" to ""),
            formats = listOf("Vinyl", "Album", "Compilation"),
            tracklist = mbEdition.trackTitles.mapIndexed { index, name ->
                "${index + 1}" to if (index == 8) "1-800-hot-n-fun" else name
            },
        )
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(mbEdition, linkedBlack, carbonBlack,
                explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(mbEdition, linkedBlack, carbonBlack,
                explicitReleaseRelation = false))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(mbEdition,
                DiscogsLink(DiscogsKind.RELEASE, 35025845),
                carbonBlack.copy(link = DiscogsLink(DiscogsKind.RELEASE, 35025845),
                    barcodes = listOf("198704525131")), explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(mbEdition, linkedBlack,
                carbonBlack.copy(tracklist = carbonBlack.tracklist.dropLast(1)),
                explicitReleaseRelation = true))
    }

    @Test fun knownDigitalMediaCannotVerifyMatchingPhysicalDiscogsCdOrVinyl() {
        // Real catalog problem: MEMORABILIA has a MusicBrainz digital
        // edition and a separate Korean Discogs CD using the same songs.
        // A copied barcode or direct Release relation cannot negate
        // an explicit physical-vs-digital contradiction.
        val digital = canon.copy(editionFormats = listOf("Digital Media"))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(digital, linked,
                record.copy(formats = listOf("CD", "EP")), explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assessUnlinked(digital,
                record.copy(formats = listOf("Vinyl", "EP"))))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(digital, linked,
                record.copy(formats = listOf("File", "EP")), explicitReleaseRelation = true))
    }

    @Test fun knownCdAndVinylMustNotVerifyWrongPhysicalMedium() {
        val compactDisc = canon.copy(editionFormats = listOf("CD"))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(compactDisc, linked,
                record.copy(formats = listOf("File", "EP"))))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assessUnlinked(compactDisc,
                record.copy(formats = listOf("Vinyl", "EP"))))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(compactDisc, linked, record))
        val vinyl = canon.copy(editionFormats = listOf("12\" Vinyl"))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(vinyl, linked,
                record.copy(formats = listOf("Vinyl", "LP", "Album"))))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(vinyl, linked, record))
    }

    @Test fun mixedMediaEditionIsCandidateNotVerifiedAsDigitalOrCdOnly() {
        val digital = canon.copy(editionFormats = listOf("Digital Media"))
        val cd = canon.copy(editionFormats = listOf("CD"))
        val mixed = record.copy(formats = listOf("File", "CD", "EP"))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(digital, linked, mixed,
                explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assessUnlinked(cd, mixed))
        // Incomplete or unfamiliar format metadata must not be invented
        // into a conflict; the other independent evidence still applies.
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(canon, linked, mixed))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(
                canon.copy(editionFormats = listOf("Unknown format")),
                linked, record.copy(formats = listOf("Vinyl", "EP"))))
    }

    @Test fun barcodeAloneOrTitleAloneIsNeverEditionProof() {
        val noTracklist = record.copy(tracklist = emptyList())
        val noBarcodes = record.copy(barcodes = emptyList())
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon.copy(creditedArtist = null), linked, record))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon.copy(barcodes = emptyList(),
                trackTitles = emptyList()), linked, record))
        // The same barcode can belong to multiple repressings or variants.
        // Without an independently comparable tracklist it stays a candidate.
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon, linked, noTracklist))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(canon, linked, noBarcodes))
    }

    @Test fun mismatchingBarcodeTracklistOrArtistDoesNotPromoteConfidence() {
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canon, linked,
                record.copy(barcodes = listOf("000000000000"))))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon, linked,
                record.copy(tracklist = listOf("1" to "Another remix"))))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canon, linked,
                record.copy(artistNames = listOf("Another Artist"))))
    }

    @Test fun onlyOfficialMusicBrainzEditionsCanBeVerifiedEvenThroughDirectRepositoryCalls() {
        // The coordinator guards Official, but the matcher is also a public
        // internal boundary used by the provider repository and future callers.
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon.copy(status = null), linked, record))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon.copy(status = "Bootleg"), linked, record))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon.copy(status = "Promotion"), linked, record))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(canon.copy(status = "official"), linked, record))
    }

    @Test fun multipleArtistsRequireExactStructuredCreditsRatherThanGuessingFromDisplayText() {
        val joint = canon.copy(
            title = "Joint EP",
            creditedArtist = "Artist A & Artist B",
            creditedArtistNames = listOf("Artist A", "Artist B"),
        )
        val discogs = record.copy(
            title = "Artist A, Artist B - Joint EP",
            artistNames = listOf("Artist A", "Artist B"),
        )
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(joint, linked, discogs))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(joint, linked,
                discogs.copy(artistNames = listOf("Artist B", "Artist A"))))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(joint, linked,
                discogs.copy(artistNames = listOf("Artist A"))))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(joint, linked,
                discogs.copy(artistNames = listOf("Artist A", "Artist C"))))
        // Do not infer a multiple-artist identity from display string alone.
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(joint.copy(creditedArtistNames = emptyList()),
                linked, discogs))
    }

    @Test fun missingStructuredMusicBrainzCreditCannotApproveAdditionalDiscogsArtists() {
        // A MusicBrainz display string alone cannot attest a second artist
        // embedded in the Discogs Release artist list. This direct matcher
        // boundary must be safe even when a caller skips the coordinator.
        val oneArtistWithoutStructuredCredit = canon.copy(
            creditedArtist = "아티스트", creditedArtistNames = emptyList(),
        )
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(oneArtistWithoutStructuredCredit, linked, record))
        val collaborativeEdition = record.copy(
            artistNames = listOf("아티스트", "Another Artist"),
        )
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(
                oneArtistWithoutStructuredCredit, linked, collaborativeEdition,
                explicitReleaseRelation = true,
            ))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assessUnlinked(
                oneArtistWithoutStructuredCredit, collaborativeEdition,
            ))
    }

    @Test fun anUnofficialPressingMustNotVerifyAnOfficialMusicBrainzRelease() {
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canon, linked, record.copy(unofficial = true)))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon.copy(status = null), linked,
                record.copy(unofficial = true)))
    }

    @Test fun editionCountryContradictionRejectsIdenticalTracklistFromAnotherPressing() {
        val exact = canon.copy(country = "KR", releaseYear = 2024)
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(exact, linked, record.copy(country = "South Korea")))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(exact.copy(country = "JP"), linked, record))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(exact.copy(country = "XW"), linked, record))
    }

    @Test fun incompatibleEditionYearCanOnlyBeCandidateEvenWhenTracklistMatches() {
        val exact = canon.copy(country = "KR", releaseYear = 2024)
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(exact.copy(releaseYear = 2020), linked, record))
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(exact.copy(releaseYear = null), linked, record))
    }

    @Test fun directReleaseLinkCanCorroborateOrderedCrossScriptTrackTitles() {
        // Observed in ENHYPEN's 2026 release: the same 11 ordered tracks,
        // five Korean vs English titles and six exact shared Latin titles.
        val musicBrainz = canon.copy(
            country = "KR", releaseYear = 2024,
            trackTitles = listOf(
                "사건의 발단", "No Way Back", "도망자들", "Knife",
                "Stealer", "우리가 찾던 목소리", "목격자",
                "Big Girls Don't Cry", "Lost Island", "Sleep Tight", "사건의 너머",
            ),
        )
        val observed = record.copy(
            country = "South Korea",
            tracklist = listOf(
                "The Beginning", "No Way Back", "The Fugitives", "Knife",
                "Stealer", "The Voice", "Witnesses", "Big Girls Don't Cry",
                "Lost Island", "Sleep Tight", "The Beyond",
            ).mapIndexed { i, title -> "${i + 1}" to title },
        )
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(musicBrainz, linked, observed,
                explicitReleaseRelation = true))
        // A search result or Master-derived edition has no direct Release URL
        // anchor and must NEVER benefit from this narrow exception.
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assessUnlinked(musicBrainz, observed))
    }

    @Test fun crossScriptLinkCannotHideWrongTrackOrderOrIncompleteEdition() {
        val original = listOf("첫번째", "Track B", "세번째", "Track D", "Track E")
        val expected = canon.copy(
            country = "KR", releaseYear = 2024, trackTitles = original,
        )
        val english = record.copy(
            country = "South Korea",
            tracklist = listOf("First", "Track B", "Third", "Track D", "Track E")
                .mapIndexed { i, title -> "${i+1}" to title },
        )
        // Five tracks, three exact comparisons, two cross-script substitutions.
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(expected, linked, english,
                explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(expected, linked,
                english.copy(tracklist = english.tracklist.reversed()), explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(expected, linked,
                english.copy(tracklist = english.tracklist.dropLast(1)), explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(expected.copy(country = "XW"), linked, english,
                explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(expected.copy(barcodes = emptyList()), linked, english,
                explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(expected.copy(releaseYear = null), linked, english,
                explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(expected, linked,
                english.copy(tracklist = english.tracklist.toMutableList().also {
                    // A Latin-to-Latin disagreement cannot be excused
                    // as Korean-to-English translation on the same Release.
                    it[1] = "2" to "Wrong Latin Title"
                }), explicitReleaseRelation = true))
    }

    @Test fun exactLinkedReleaseCanCorroborateOnlySafePunctuationVariants() {
        // Public control: EASY CRAZY HOT <-> Easy-Crazy-Hot has a typed direct
        // Release relation, matching barcode, and 14 of 15 literal track names.
        val canonical = canon.copy(
            title = "EASY CRAZY HOT", creditedArtist = "LE SSERAFIM",
            barcodes = listOf("012345678905"),
            country = "XW", releaseYear = 2025,
            trackTitles = (1..15).map { if (it == 9) "1‐800‐hot‐n‐fun" else "Track $it" },
        )
        val edition = record.copy(
            title = "Easy-Crazy-Hot", artistNames = listOf("LE SSERAFIM"),
            country = "South Korea", year = 2025,
            tracklist = (1..15).map { index ->
                index.toString() to (if (index == 9) "1-800-hot-n-fun" else "Track $index")
            },
        )
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(canonical, linked, edition,
                explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canonical, linked, edition))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assessUnlinked(canonical, edition))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canonical.copy(barcodes = emptyList()),
                linked, edition, explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canonical.copy(releaseYear = null),
                linked, edition, explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canonical.copy(country = "JP"),
                linked, edition, explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canonical, linked,
                edition.copy(tracklist = edition.tracklist.toMutableList().also {
                    it[8] = "9" to "Different Recording"
                }), explicitReleaseRelation = true))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canonical, linked,
                edition.copy(tracklist = edition.tracklist.toMutableList().also {
                    it[4] = "5" to "New Remix"
                    it[8] = "9" to "1-800-hot-n-fun"
                    it[11] = "12" to "Changed"
                }), explicitReleaseRelation = true))
    }

    @Test fun separateMastersOrNameOnlyMatchesNeverCreateAnEdition() {
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canon, DiscogsLink(DiscogsKind.MASTER,12001),record))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon.copy(trackTitles = emptyList(),
                barcodes = emptyList()),linked,record))
    }
}
