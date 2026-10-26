package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/**
 * A repeated metadata claim is a POSSIBLE alternative source, not proof of
 * identical bytes, encoding, identity or an approved unattended download.
 */
class A0CandidateClaimClustersTest {
    private val strong=A0CandidatePreflight.Assessment(A0CandidatePreflight.Confidence.STRONG,emptyList())
    private val review=A0CandidatePreflight.Assessment(
        A0CandidatePreflight.Confidence.REVIEW,
        listOf(A0CandidatePreflight.Reason.DURATION_UNCONFIRMED))
    private fun entry(
        filename:String="ILLIT Magnetic.mp3",size:Long=6_400_000L,
        bitrate:Int?=320, duration:Int?=161,
        score:Int=90,ready:Boolean=true,
        assessment:A0CandidatePreflight.Assessment=strong
    )=A0CandidateClaimClusters.Entry(
        claim=A0CandidateClaimClusters.Claim(
            filename=filename,format="mp3",sizeBytes=size,bitrateKbps=bitrate,
            durationSeconds=duration,sampleRateHz=null,bitDepth=null),
        assessment=assessment,score=score,
        availability=A0PeerAvailability.assess(
            true,false,if(ready)0 else 40,if(ready)128_000 else 4_096)
    )

    @Test fun twoPeersWithSameDeclaredFileAreOneApparentClaimNotTwoVersions() {
        val result=A0CandidateClaimClusters.preview(listOf(entry(ready=false),entry(ready=true)))
        assertEquals(1,result.distinctClaims)
        assertEquals(1,result.repeatedClaims)
        assertEquals(A0CandidatePreflight.SimulationStatus.SUGGESTED,result.status)
        assertEquals(1,result.preferredIndex)
    }

    @Test fun repeatedClaimIsNotVerificationOfRealAudio() {
        val result=A0CandidateClaimClusters.preview(listOf(
            entry(),entry(),entry()))
        assertEquals(1,result.distinctClaims)
        assertEquals(2,result.repeatedClaims)
        assertEquals(3,result.totalCandidates)
    }

    @Test fun oneByteOrOneSecondDifferenceRetainsSeparateUncertainClaims() {
        for(other in listOf(entry(size=6_400_001),entry(duration=162),
                entry(filename="ILLIT Magnetic - remaster.mp3"))) {
            val result=A0CandidateClaimClusters.preview(listOf(entry(),other))
            assertEquals(2,result.distinctClaims)
            assertEquals(A0CandidatePreflight.SimulationStatus.AMBIGUOUS,result.status)
            assertNull(result.preferredIndex)
        }
    }

    @Test fun fasterSourceCannotOverrideDistinctHighQualityCandidate() {
        val result=A0CandidateClaimClusters.preview(listOf(
            entry(score=92,ready=false),
            entry(filename="ILLIT Magnetic studio.mp3",score=72,ready=true)
        ))
        assertEquals(A0CandidatePreflight.SimulationStatus.SUGGESTED,result.status)
        assertEquals(0,result.preferredIndex)
    }

    @Test fun onlyReviewOrUnsafeOptionsAlwaysAbstain() {
        val result=A0CandidateClaimClusters.preview(listOf(entry(assessment=review)))
        assertEquals(A0CandidatePreflight.SimulationStatus.NO_STRONG,result.status)
        assertNull(result.preferredIndex)
    }

    @Test fun emptyCandidateSetIsSideEffectFreeAndAbstains() {
        val result=A0CandidateClaimClusters.preview(emptyList())
        assertEquals(0,result.distinctClaims)
        assertEquals(0,result.totalCandidates)
        assertEquals(A0CandidatePreflight.SimulationStatus.NO_STRONG,result.status)
    }

    @Test fun caseAndUnicodeNormalizationOnlyConflateExactMetadataOtherwise() {
        val one=entry(filename="ILLIT MAGNETIC.MP3")
        val two=entry(filename="illit magnetic.mp3")
        val result=A0CandidateClaimClusters.preview(listOf(one,two))
        assertEquals(1,result.distinctClaims)
    }
    @Test fun identicalFileClaimsWithDifferentPreflightRisksAreNotSameEvidence() {
        val result=A0CandidateClaimClusters.preview(listOf(
            entry(assessment=strong),entry(assessment=review)))
        assertEquals(2,result.distinctClaims)
        assertEquals(0,result.repeatedClaims)
        assertEquals(A0CandidatePreflight.SimulationStatus.SUGGESTED,result.status)
    }


    @Test fun shadowReadinessOnlyConsidersEligibleClaimsButKeepsFullDiscoveryCounts() {
        val entries=listOf(
            entry(filename="ILLIT Magnetic.flac",score=99),
            entry(filename="ILLIT Magnetic.mp3",score=90),
        )
        val compatible=A0CandidateClaimClusters.preview(entries,eligibleIndices=setOf(1))
        assertEquals(A0CandidatePreflight.SimulationStatus.SUGGESTED,compatible.status)
        assertEquals(1,compatible.preferredIndex)
        assertEquals(2,compatible.totalCandidates)
        assertEquals(2,compatible.distinctClaims)
        val abstain=A0CandidateClaimClusters.preview(entries,eligibleIndices=emptySet())
        assertEquals(A0CandidatePreflight.SimulationStatus.NO_STRONG,abstain.status)
        assertNull(abstain.preferredIndex)
        assertEquals(2,abstain.distinctClaims)
    }
}
