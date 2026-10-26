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
            format=ext,sizeBytes=size,
            declaredSampleRateHz=if(ext=="flac") 44100 else null,
            declaredBitDepth=if(ext=="flac") 16 else null)
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
        // 128 kbps at 161s needs roughly 2.6 MB; keep file size consistent
        // to exercise the low-quality REVIEW branch, not a size conflict.
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,
            inspect(bitrate=128,size=2_576_000).confidence)
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

    @Test fun conflictingFilenameCreditMakesCandidateUnsafeDespiteMatchingFolder() {
        val ranking=A0CandidateRank.rank(
            "ILLIT - Magnetic","Other Artist - Magnetic.mp3",320,
            remotePath="Music\\ILLIT\\Other Artist - Magnetic.mp3",format="mp3",
            declaredDurationSeconds=161,expectedDurationSeconds=161,sizeBytes=6_400_000L)
        val candidate=A0CandidatePreflight.assess(
            "ILLIT - Magnetic",ranking,320,
            declaredDurationSeconds=161,expectedDurationSeconds=161,
            format="mp3",sizeBytes=6_400_000L)
        assertEquals(A0CandidatePreflight.Confidence.UNSAFE,candidate.confidence)
        assertTrue(A0CandidatePreflight.Reason.ARTIST_CONFLICT in candidate.reasons)
    }

    @Test fun unexplainedFilenameTokensRequireReviewNotAutomaticChoice() {
        val ranking=A0CandidateRank.rank(
            "ILLIT - Magnetic","Other Artist Magnetic.mp3",320,
            remotePath="Music\\ILLIT\\Other Artist Magnetic.mp3",format="mp3",
            declaredDurationSeconds=161,expectedDurationSeconds=161,
            sizeBytes=6_400_000L)
        val result=A0CandidatePreflight.assess(
            "ILLIT - Magnetic",ranking,320,
            declaredDurationSeconds=161,expectedDurationSeconds=161,
            format="mp3",sizeBytes=6_400_000L)
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,result.confidence)
        assertTrue(A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS in result.reasons)
    }

    @Test fun simulationReturnsTypedAbstentionWithoutTransferring() {
        val strong=inspect()
        val review=inspect(duration=null)
        assertEquals(A0CandidatePreflight.SimulationStatus.NO_STRONG,
            A0CandidatePreflight.simulate(listOf(A0CandidatePreflight.Weighted(review,100))).status)
        assertEquals(A0CandidatePreflight.SimulationStatus.AMBIGUOUS,
            A0CandidatePreflight.simulate(listOf(
                A0CandidatePreflight.Weighted(strong,90),
                A0CandidatePreflight.Weighted(strong,89))).status)
        val decision=A0CandidatePreflight.simulate(listOf(
            A0CandidatePreflight.Weighted(review,99),
            A0CandidatePreflight.Weighted(strong,90)))
        assertEquals(A0CandidatePreflight.SimulationStatus.SUGGESTED,decision.status)
        assertEquals(1,decision.index)
    }

    @Test fun shuffledMultiwordTitleCannotBeStrongMerelyFromMatchingWords() {
        val rank=A0CandidateRank.rank("Artist - Save Your Tears",
            "Artist Tears Your Save.mp3",320,
            remotePath="Music/Artist/Artist Tears Your Save.mp3",
            format="mp3",declaredDurationSeconds=215,expectedDurationSeconds=215,
            sizeBytes=8_600_000L)
        val outcome=A0CandidatePreflight.assess("Artist - Save Your Tears",
            rank,320,declaredDurationSeconds=215,expectedDurationSeconds=215,
            format="mp3",sizeBytes=8_600_000L)
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,outcome.confidence)
        assertTrue(A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS in outcome.reasons)
    }

    @Test fun repeatedTitleWordsCannotBeFakedByOneOccurrence() {
        val rank=A0CandidateRank.rank("Artist - Do Do Do","Artist Do.mp3",320,
            remotePath="Music/Artist/Artist Do.mp3",format="mp3",
            declaredDurationSeconds=161,expectedDurationSeconds=161,
            sizeBytes=6_400_000L)
        val outcome=A0CandidatePreflight.assess("Artist - Do Do Do",
            rank,320,declaredDurationSeconds=161,expectedDurationSeconds=161,
            format="mp3",sizeBytes=6_400_000L)
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,outcome.confidence)
    }

    @Test fun correctlyOrderedMultiwordTitleRemainsStrong() {
        val rank=A0CandidateRank.rank("Artist - Save Your Tears",
            "Artist Save Your Tears.mp3",320,
            remotePath="Music/Artist/Artist Save Your Tears.mp3",
            format="mp3",declaredDurationSeconds=215,expectedDurationSeconds=215,
            sizeBytes=8_600_000L)
        val outcome=A0CandidatePreflight.assess("Artist - Save Your Tears",
            rank,320,declaredDurationSeconds=215,expectedDurationSeconds=215,
            format="mp3",sizeBytes=8_600_000L)
        assertEquals(A0CandidatePreflight.Confidence.STRONG,outcome.confidence)
    }

    @Test fun unrequestedRemasterOrRadioEditIsNotPromotedAsOriginalMaster() {
        listOf(
            "Michael Jackson Billie Jean (2015 Remastered).mp3" to
                "Music/Michael Jackson/Thriller",
            "Michael Jackson Billie Jean.mp3" to
                "Music/Michael Jackson/Thriller (2015 Remastered)",
            "Michael Jackson Billie Jean (Radio Edit).mp3" to
                "Music/Michael Jackson/Thriller",
        ).forEach { (name,path) ->
            val rank=A0CandidateRank.rank("Michael Jackson - Billie Jean",name,320,
                remotePath="$path/$name",format="mp3",
                declaredDurationSeconds=294,expectedDurationSeconds=294,
                sizeBytes=11_760_000L)
            val assessed=A0CandidatePreflight.assess("Michael Jackson - Billie Jean",
                rank,320,294,294,"mp3",11_760_000L)
            assertTrue(name, A0CandidateRank.Warning.VERSION_MISMATCH in rank.warnings)
            assertEquals(name,A0CandidatePreflight.Confidence.UNSAFE,assessed.confidence)
            assertTrue(name,A0CandidatePreflight.Reason.VERSION_CONFLICT in assessed.reasons)
        }
    }

    @Test fun explicitlyRequestedRadioEditDoesNotCountAsVersionConflict() {
        val query="Michael Jackson - Billie Jean Radio Edit"
        val name="Michael Jackson Billie Jean Radio Edit.mp3"
        val rank=A0CandidateRank.rank(query,name,320,
            remotePath="Music/Michael Jackson/Thriller/$name",format="mp3",
            declaredDurationSeconds=230,expectedDurationSeconds=230,sizeBytes=9_200_000L)
        assertFalse(A0CandidateRank.Warning.VERSION_MISMATCH in rank.warnings)
    }


    @Test fun compatibleAdtsAac256CanBeStrongOnPlausibleMetadataOnly() {
        val assessed=inspect(name="ILLIT - Magnetic.aac",ext="aac",
            bitrate=256,size=5_150_000L)
        assertEquals(A0CandidatePreflight.Confidence.STRONG,assessed.confidence)
        assertTrue(assessed.reasons.isEmpty())
    }

    @Test fun aacClaimTooLowOrImplausiblyHighRequiresManualReview() {
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,
            inspect(name="ILLIT - Magnetic.aac",ext="aac",
                bitrate=128,size=2_576_000L).confidence)
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,
            inspect(name="ILLIT - Magnetic.aac",ext="aac",
                bitrate=768,size=15_456_000L).confidence)
    }
}
