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

    @Test fun confirmedEditionNeedsAnExactDiscogsReleaseLinkAndIndependentCorroboration() {
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(canon, linked, record))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canon, DiscogsLink(DiscogsKind.RELEASE, 999), record))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canon, DiscogsLink(DiscogsKind.MASTER, 12001), record))
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

    @Test fun separateMastersOrNameOnlyMatchesNeverCreateAnEdition() {
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canon, DiscogsLink(DiscogsKind.MASTER,12001),record))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon.copy(trackTitles = emptyList(),
                barcodes = emptyList()),linked,record))
    }
}
