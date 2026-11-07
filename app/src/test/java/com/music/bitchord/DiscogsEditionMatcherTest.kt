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

    @Test fun separateMastersOrNameOnlyMatchesNeverCreateAnEdition() {
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(canon, DiscogsLink(DiscogsKind.MASTER,12001),record))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assess(canon.copy(trackTitles = emptyList(),
                barcodes = emptyList()),linked,record))
    }
}
