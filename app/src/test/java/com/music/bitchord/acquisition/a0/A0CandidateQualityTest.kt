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
}
