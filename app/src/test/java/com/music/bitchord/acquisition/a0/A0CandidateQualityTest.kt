package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0CandidateQualityTest {
    @Test fun folderArtistMatchCanIdentifyTrackWhenFilenameIsTitleOnly() {
        val relevant=A0CandidateRank.rank("ILLIT - Magnetic", "Magnetic.mp3",
            320,remotePath="K-Pop\\ILLIT\\Super Real Me\\Magnetic.mp3",format="mp3")
        val unrelated=A0CandidateRank.rank("ILLIT - Magnetic", "Magnetic.mp3",
            320,remotePath="Music\\Elsewhere\\Magnetic.mp3",format="mp3")
        assertTrue(relevant.score>unrelated.score)
        assertEquals(A0CandidateRank.Match.ALL_TERMS,relevant.match)
    }
    @Test fun matchingOfficialLosslessOutranksRemixAndLowBitrateMp3() {
        val flac=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT - Magnetic.flac",null,
            format="flac",declaredSampleRateHz=44100,declaredBitDepth=16,
            declaredDurationSeconds=161,expectedDurationSeconds=161)
        val remix=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT Magnetic live remix.mp3",96,
            format="mp3",declaredDurationSeconds=161,expectedDurationSeconds=161)
        val goodMp3=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT Magnetic.mp3",320,
            format="mp3",declaredDurationSeconds=161,expectedDurationSeconds=161)
        assertTrue(flac.score>remix.score)
        assertTrue(goodMp3.score>remix.score)
        assertEquals(A0CandidateRank.Quality.LOSSLESS_ADVERTISED,flac.quality)
        assertEquals(A0CandidateRank.Quality.LOW_ADVERTISED,remix.quality)
    }
    @Test fun durationMismatchAndImpossibleSizeArePenalizedAsSuspicious() {
        val good=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT - Magnetic.mp3",320,
            format="mp3",declaredDurationSeconds=161,expectedDurationSeconds=161,
            sizeBytes=6_400_000)
        val wrong=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT - Magnetic.mp3",320,
            format="mp3",declaredDurationSeconds=400,expectedDurationSeconds=161,
            sizeBytes=100_000)
        assertTrue(good.score>wrong.score)
        assertTrue(wrong.warnings.contains(A0CandidateRank.Warning.DURATION_MISMATCH))
        assertTrue(wrong.warnings.contains(A0CandidateRank.Warning.SIZE_SUSPICIOUS))
    }
    @Test fun claimedUnknownQualityDoesNotEqualVerifiedLossless() {
        val unknown=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT Magnetic.flac",null,format="flac")
        assertEquals(A0CandidateRank.Quality.UNKNOWN_ADVERTISED,unknown.quality)
    }
    @Test fun requestedLiveVersionIsNotFlaggedAsUnwantedRemix() {
        val studio=A0CandidateRank.rank("Artist - Song", "Artist Song live.mp3",320,format="mp3")
        val requested=A0CandidateRank.rank("Artist - Song live", "Artist Song live.mp3",320,format="mp3")
        assertTrue(A0CandidateRank.Warning.VERSION_MISMATCH in studio.warnings)
        assertFalse(A0CandidateRank.Warning.VERSION_MISMATCH in requested.warnings)
    }
    @Test fun impossibleClaimedMp3BitrateDoesNotCountAsHighQuality() {
        val fake=A0CandidateRank.rank("Artist - Song","Artist Song.mp3",1000,format="mp3")
        assertNotEquals(A0CandidateRank.Quality.HIGH_ADVERTISED,fake.quality)
        assertTrue(A0CandidateRank.Warning.ENCODING_CONFLICT in fake.warnings)
    }

    @Test fun conflictingFilenameArtistCannotBorrowArtistFromParentFolder() {
        val candidate=A0CandidateRank.rank(
            "ILLIT - Magnetic", "Other Artist - Magnetic.mp3",320,
            remotePath="Music\\ILLIT\\Other Artist - Magnetic.mp3", format="mp3",
            declaredDurationSeconds=161, expectedDurationSeconds=161,
            sizeBytes=6_400_000L
        )
        assertTrue(A0CandidateRank.Warning.ARTIST_CONFLICT in candidate.warnings)
    }

    @Test fun trackNumberPrefixIsNotConflictingArtist() {
        val candidate=A0CandidateRank.rank(
            "ILLIT - Magnetic", "01 - Magnetic.mp3",320,
            remotePath="Music\\ILLIT\\01 - Magnetic.mp3",format="mp3")
        assertEquals(A0CandidateRank.Match.ALL_TERMS,candidate.match)
        assertFalse(A0CandidateRank.Warning.ARTIST_CONFLICT in candidate.warnings)
    }

    @Test fun artistTokensSplitAcrossUnrelatedFoldersAreNotAConfirmedArtist() {
        val candidate=A0CandidateRank.rank(
            "New Jeans - OMG", "OMG.mp3",320,
            remotePath="Music\\New\\Jeans\\OMG.mp3",format="mp3")
        assertNotEquals(A0CandidateRank.Match.ALL_TERMS,candidate.match)
    }

    @Test fun unexplainedExtraArtistWordsWarnEvenWithoutDash() {
        val ranking=A0CandidateRank.rank(
            "ILLIT - Magnetic","Other Artist Magnetic.mp3",320,
            remotePath="Music\\ILLIT\\Other Artist Magnetic.mp3",format="mp3")
        assertTrue(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in ranking.warnings)
    }

    @Test fun ordinaryTrackNumberAndOfficialAudioLabelDoNotCreateIdentityWarning() {
        val plain=A0CandidateRank.rank("ILLIT - Magnetic","01 - Magnetic.mp3",320,
            remotePath="Music\\ILLIT\\01 - Magnetic.mp3",format="mp3")
        val official=A0CandidateRank.rank("ILLIT - Magnetic",
            "ILLIT - Magnetic (Official Audio).mp3",320,
            remotePath="Music\\ILLIT\\ILLIT - Magnetic (Official Audio).mp3",
            format="mp3")
        assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in plain.warnings)
        assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in official.warnings)
    }

    @Test fun versionHintsInImmediateAlbumFolderAreNotTrustedAsStudio() {
        val rank=A0CandidateRank.rank("ILLIT - Magnetic","Magnetic.mp3",320,
            remotePath="Music\\ILLIT\\Magnetic (Live)\\Magnetic.mp3",
            format="mp3",declaredDurationSeconds=161,expectedDurationSeconds=161,
            sizeBytes=6_400_000)
        assertEquals(A0CandidateRank.Match.ALL_TERMS,rank.match)
        assertTrue(A0CandidateRank.Warning.VERSION_MISMATCH in rank.warnings)
    }

    @Test fun karaokeAlbumFolderIsVersionConflictButUnrelatedAncestorDoesNotForceIt() {
        val karaoke=A0CandidateRank.rank("ILLIT - Magnetic","Magnetic.mp3",320,
            remotePath="Music\\ILLIT\\Karaoke\\Magnetic.mp3",format="mp3")
        assertTrue(A0CandidateRank.Warning.VERSION_MISMATCH in karaoke.warnings)
        val upstream=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT Magnetic.mp3",320,
            remotePath="Music\\Live Archive\\ILLIT\\ILLIT Magnetic.mp3",format="mp3")
        assertFalse(A0CandidateRank.Warning.VERSION_MISMATCH in upstream.warnings)
    }

    @Test fun impossiblyLowOrNegativeMp3ClaimsAreEncodingConflicts() {
        for(rate in listOf(-1,0,16,2_000_000)) {
            val candidate=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT Magnetic.mp3",rate,
                format="mp3")
            assertTrue("Claim "+rate+" was silently ignored",
                A0CandidateRank.Warning.ENCODING_CONFLICT in candidate.warnings)
        }
    }


    @Test fun extraOccurrencesOfArtistOrTitleWordsMustBeReviewed() {
        for(name in listOf(
            "ILLIT Magnetic Magnetic.mp3",
            "ILLIT ILLIT Magnetic.mp3",
            "ILLIT Magnetic ILLIT.mp3"
        )) {
            val rank=A0CandidateRank.rank("ILLIT - Magnetic",name,320,
                remotePath="Music/ILLIT/"+name,format="mp3",
                declaredDurationSeconds=161,expectedDurationSeconds=161,
                sizeBytes=6_400_000L)
            assertTrue("Duplicated identity in "+name,
                A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in rank.warnings)
            val result=A0CandidatePreflight.assess("ILLIT - Magnetic",rank,320,
                declaredDurationSeconds=161,expectedDurationSeconds=161,
                format="mp3",sizeBytes=6_400_000L)
            assertEquals(A0CandidatePreflight.Confidence.REVIEW,result.confidence)
            assertTrue(A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS in result.reasons)
        }
    }

    @Test fun validRepeatedTitleWordsAndArtistFolderRemainStrong() {
        val cases=listOf(
            Triple("Artist - Do Do Do","Artist Do Do Do.mp3",
                "Music/Artist/Artist Do Do Do.mp3"),
            Triple("ILLIT - Magnetic","Magnetic.mp3",
                "Music/ILLIT/Magnetic.mp3"),
            Triple("ILLIT - Magnetic","ILLIT Magnetic (Official Audio).mp3",
                "Music/ILLIT/ILLIT Magnetic (Official Audio).mp3")
        )
        for((query,name,path) in cases) {
            val rank=A0CandidateRank.rank(query,name,320,
                remotePath=path,format="mp3",
                declaredDurationSeconds=161,expectedDurationSeconds=161,
                sizeBytes=6_400_000L)
            assertFalse("Valid identity: "+name,
                A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in rank.warnings)
            assertEquals(A0CandidatePreflight.Confidence.STRONG,
                A0CandidatePreflight.assess(query,rank,320,
                    declaredDurationSeconds=161,expectedDurationSeconds=161,
                    format="mp3",sizeBytes=6_400_000L).confidence)
        }
    }
}
