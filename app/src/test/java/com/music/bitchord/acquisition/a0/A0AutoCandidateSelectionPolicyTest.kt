package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import com.music.bitchord.data.settings.DownloadQualityProfile
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class A0AutoCandidateSelectionPolicyTest {
    private val profile=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
    private val target=SoulseekBatchPlan.Entry(
        "magnetic","ILLIT - Magnetic","SUPER REAL ME",161)
    private fun offers(
        peer:String,
        name:String="ILLIT - Magnetic.mp3",
        duration:Int=161,
        bitrate:Int=320,
        album:String="SUPER REAL ME",
        speed:Int=128_000,
        queue:Int=0,
        sizeBytes:Long?=null,
    ):List<A0ObservedCandidateHandoff.Choice> {
        val source=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase=target.query,requestedAlbum=target.album,
            expectedDurationSeconds=target.durationSeconds,qualityProfile=profile,
            collectReleaseShadow=true)
        val file=A0SoulseekSearchFile(
            "Shared\\ILLIT\\$album\\$name",
            sizeBytes ?: duration*40_000L,"mp3",mapOf(0 to bitrate,1 to duration))
        source.offerParsed(A0SoulseekSearchResponse(
            peer,177,listOf(file),emptyList(),true,speed,queue),peer)
        return source.releaseShadowChoices()
    }

    @Test fun strongReadySingleTrackSuggestsButCannotTransfer() {
        val selection=A0AutoCandidateSelectionPolicy.preview(
            target,offers("source-one"),profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,selection.status)
        assertEquals(1,selection.sequentialAlternatives.size)
        assertEquals(1,selection.eligible)
        assertFalse(selection.canTransfer)
        assertFalse(selection.toString().contains("source-one"))
    }

    @Test fun identicalClaimsFromDifferentPeersSupplyAtMostThreeSequentialOptions() {
        val all=(1..5).flatMap { offers("peer-$it") }
        val selection=A0AutoCandidateSelectionPolicy.preview(target,all,profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,selection.status)
        assertEquals(3,selection.sequentialAlternatives.size)
        assertEquals(3,selection.sequentialAlternatives.map {
            it.peerUsername }.distinct().size)
        assertFalse(selection.canTransfer)
    }

    @Test fun harmlessTrackNumberPrefixWithIdenticalTechnicalClaimsDoesNotForceReview() {
        val a=offers("one")
        val b=offers("two",name="01 ILLIT - Magnetic.mp3")
        val choice=A0AutoCandidateSelectionPolicy.preview(target,a+b,profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,choice.status)
        assertEquals(2,choice.sequentialAlternatives.size)
    }

    @Test fun conflictingExplicitTrackNumbersNeverBecomeOneGroupThroughUnnumberedPeer() {
        val first=offers("unnumbered")
        val second=offers("one",name="01 ILLIT - Magnetic.mp3")
        val third=offers("two",name="02 ILLIT - Magnetic.mp3")
        val a=A0AutoCandidateSelectionPolicy.preview(
            target,first+second+third,profile)
        val b=A0AutoCandidateSelectionPolicy.preview(
            target,second+first+third,profile)
        for(decision in listOf(a,b)) {
            assertEquals(A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,
                decision.status)
            assertEquals(A0AutoCandidateSelectionPolicy.Reason.AMBIGUOUS_EDITION,
                decision.reason)
        }
    }

    @Test fun differentFileSizesStillForceReviewDespiteIdenticalTrackNames() {
        val a=offers("one")
        val b=offers("two",sizeBytes=161*40_000L+1024L)
        val choice=A0AutoCandidateSelectionPolicy.preview(target,a+b,profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,choice.status)
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.AMBIGUOUS_EDITION,choice.reason)
        assertTrue(choice.sequentialAlternatives.isEmpty())
    }

    @Test fun exactAlbumWithinOneGenericAudioSubfolderIsStillEligible() {
        for(folder in listOf("FLAC","CD1","Disc 2","Lossless")) {
            val decision=A0AutoCandidateSelectionPolicy.preview(
                target,offers("source",album="SUPER REAL ME\\$folder"),profile)
            assertEquals("generic folder $folder beneath exact album",
                A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,
                decision.status)
        }
    }

    @Test fun matchingArtistOrConflictingParentMustNeverProveAlbum() {
        val bad=A0AutoCandidateSelectionPolicy.preview(
            target,offers("source",album="Other Album\\FLAC"),profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,bad.status)
        val noAlbum=A0AutoCandidateSelectionPolicy.preview(
            target,offers("source",album="ILLIT\\FLAC"),profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,noAlbum.status)
    }

    @Test fun albumMismatchLowQualityAndMissingCanonicalDurationAreNotPromoted() {
        val wrongAlbum=A0AutoCandidateSelectionPolicy.preview(
            target,offers("a",album="Other Album"),profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,wrongAlbum.status)
        val wrongQuality=A0AutoCandidateSelectionPolicy.preview(
            target,offers("a",bitrate=128),profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.NO_STRONG_CANDIDATE,
            wrongQuality.reason)
        val missing=A0AutoCandidateSelectionPolicy.preview(
            target.copy(durationSeconds=null),offers("a"),profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.INVALID_TARGET,missing.reason)
    }

    @Test fun fullQueueOrCutOffSampleCannotClaimAutomaticReadiness() {
        val congested=A0AutoCandidateSelectionPolicy.preview(
            target,offers("a",queue=40),profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.NO_READY_SOURCE,congested.reason)
        val truncated=A0AutoCandidateSelectionPolicy.preview(
            target,offers("a"),profile,sampleTruncated=true)
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.INCOMPLETE_SAMPLE,truncated.reason)
    }

    @Test fun otherAlbumTrackMustBeRescoredAgainstOwnCanonicalIdentity() {
        val sibling=target.copy(trackId="lucky",query="ILLIT - Lucky Girl Syndrome",
            durationSeconds=160)
        val wrong=A0AutoCandidateSelectionPolicy.preview(
            sibling,offers("a"),profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,wrong.status)
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.NO_STRONG_CANDIDATE,wrong.reason)
    }

    @Test fun multipleSearchesCannotSilentlyAcceptContradictoryPeerClaims() {
        val one=offers("peer")
        val changed=offers("peer",bitrate=256)
        val result=A0AutoCandidateSelectionPolicy.preview(target,one+changed,profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.CONTRADICTORY_SOURCE,result.reason)
        assertFalse(result.canTransfer)
    }
}
