package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** #177: scored != safe to auto-select. All observations are peer-declared. */
class A0CandidatePreflightTest {
    private fun inspect(
        query:String="ILLIT - Magnetic", name:String="ILLIT - Magnetic.mp3",
        bitrate:Int?=320, duration:Int?=161, expected:Int?=161,
        size:Long=6_400_000L, ext:String="mp3"
    ):A0CandidatePreflight.Assessment {
        val ranked=A0CandidateRank.rank(query,name,bitrate,
            remotePath="Music\\ILLIT\\$name",format=ext,
            declaredDurationSeconds=duration,expectedDurationSeconds=expected,
            declaredSampleRateHz=if(ext=="flac") 44100 else null,
            declaredBitDepth=if(ext=="flac") 16 else null,
            sizeBytes=size)
        return A0CandidatePreflight.assess(query, ranked, bitrate,
            declaredDurationSeconds=duration, expectedDurationSeconds=expected,
            format=ext,sizeBytes=size)
    }

    @Test fun completeHighQualityClaimAndDurationIsStrongButNeverVerifiedAudio() {
        val result=inspect()
        assertEquals(A0CandidatePreflight.Confidence.STRONG, result.confidence)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun titleOnlyQueryMustNotBecomeAutomaticDecision() {
        val result=inspect(query="Magnetic",name="Magnetic.mp3")
        assertEquals(A0CandidatePreflight.Confidence.REVIEW, result.confidence)
        assertTrue(A0CandidatePreflight.Reason.ARTIST_UNCONFIRMED in result.reasons)
    }

    @Test fun unknownDurationRequiresManualReviewEvenWithArtistAndBitrate() {
        val result=inspect(duration=null)
        assertEquals(A0CandidatePreflight.Confidence.REVIEW, result.confidence)
        assertTrue(A0CandidatePreflight.Reason.DURATION_UNCONFIRMED in result.reasons)
    }

    @Test fun noCanonicalExpectedDurationAlsoNeedsReview() {
        val result=inspect(expected=null)
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,result.confidence)
    }

    @Test fun incorrectVersionOrDurationMustNotBeStrong() {
        assertEquals(A0CandidatePreflight.Confidence.UNSAFE,inspect(name="ILLIT Magnetic live.mp3").confidence)
        assertEquals(A0CandidatePreflight.Confidence.UNSAFE,inspect(duration=250).confidence)
    }

    @Test fun highAdvertisedBitrateWithoutPlausibleBytesIsUnsafe() {
        val result=inspect(size=100_000)
        assertEquals(A0CandidatePreflight.Confidence.UNSAFE,result.confidence)
        assertTrue(A0CandidatePreflight.Reason.SIZE_CONFLICT in result.reasons)
    }

    @Test fun lowOrMissingBitrateCannotBeStrong() {
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,inspect(bitrate=null).confidence)
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,inspect(bitrate=128).confidence)
    }

    @Test fun flacWithDeclaredAudioAttributesCanBeStrongButUnverified() {
        val result=inspect(name="ILLIT Magnetic.flac",bitrate=null,size=24_000_000L,ext="flac")
        assertEquals(A0CandidatePreflight.Confidence.STRONG,result.confidence)
    }

    @Test fun missingArtistAndWrongTitleIsUnsafeRegardlessOfQuality() {
        val result=inspect(name="not-the-song.mp3")
        assertEquals(A0CandidatePreflight.Confidence.UNSAFE,result.confidence)
        assertTrue(A0CandidatePreflight.Reason.TITLE_MISMATCH in result.reasons)
    }

    @Test fun futureRecommendationAbstainsWhenTwoStrongCandidatesTie() {
        val a=inspect()
        val b=inspect()
        assertNull(A0CandidatePreflight.suggestIndex(listOf(
            A0CandidatePreflight.Weighted(a,95),
            A0CandidatePreflight.Weighted(b,95))))
        assertEquals(0,A0CandidatePreflight.suggestIndex(listOf(
            A0CandidatePreflight.Weighted(a,95),
            A0CandidatePreflight.Weighted(b,75))))
    }

    @Test fun noStrongCandidateMeansNoUnattendedRecommendation() {
        assertNull(A0CandidatePreflight.suggestIndex(listOf(
            A0CandidatePreflight.Weighted(inspect(duration=null),95),
            A0CandidatePreflight.Weighted(inspect(name="ILLIT Magnetic remix.mp3"),50))))
    }
    @Test fun claimedOneThousandKbpsMp3IsUnsafe() {
        val result=inspect(bitrate=1000)
        assertEquals(A0CandidatePreflight.Confidence.UNSAFE,result.confidence)
        assertTrue(A0CandidatePreflight.Reason.ENCODING_CONFLICT in result.reasons)
    }

    @Test fun eightBitLosslessClaimRequiresReview() {
        val rank=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT Magnetic.flac",null,
            remotePath="Music\\\\ILLIT\\\\ILLIT Magnetic.flac",format="flac",
            declaredDurationSeconds=161,expectedDurationSeconds=161,
            declaredSampleRateHz=44100,declaredBitDepth=8,sizeBytes=24_000_000)
        val assessed=A0CandidatePreflight.assess("ILLIT - Magnetic",rank,null,
            declaredDurationSeconds=161,expectedDurationSeconds=161,
            format="flac",sizeBytes=24_000_000,
            declaredSampleRateHz=44100,declaredBitDepth=8)
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,assessed.confidence)
    }

}
