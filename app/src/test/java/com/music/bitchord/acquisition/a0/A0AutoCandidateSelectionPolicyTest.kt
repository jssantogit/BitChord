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

    @Test fun rankMissingTermsAreDiagnosedAsTitleOrArtistWithoutLeakingNames() {
        val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase=target.query,requestedAlbum=target.album,
            expectedDurationSeconds=target.durationSeconds,
            qualityProfile=profile,collectReleaseShadow=true,
            // Production automatic collectors provide this release's titles
            // to enable the early filename screen; an empty query list
            // intentionally keeps all files for legacy/manual use.
            releaseQueries=listOf(target.query))
        val files=listOf(
            // Correct song title without an independently credited artist.
            A0SoulseekSearchFile(
                "Shared\\Other\\SUPER REAL ME\\Magnetic.mp3",
                6_440_000L,"mp3",mapOf(0 to 320,1 to 161)),
            // Correct artist folder but a different song title.
            A0SoulseekSearchFile(
                "Shared\\ILLIT\\SUPER REAL ME\\Other.mp3",
                6_440_000L,"mp3",mapOf(0 to 320,1 to 161)),
        )
        assertTrue(collector.offerParsed(A0SoulseekSearchResponse(
            "peer",177,files,emptyList(),true,128_000,0),"peer"))
        // Combined manual+shadow collectors intentionally retain all manual
        // filenames; the production auto-only collector applies the early
        // title filter (covered separately in A0ObservedCandidateHandoffTest).
        // Exercise missing title AND missing artist on this manual sample.
        assertEquals(2,collector.choices().size)
        val choice=A0AutoCandidateSelectionPolicy.preview(
            target,collector.choices(),profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.NO_STRONG_CANDIDATE,
            choice.reason)
        assertEquals(2,choice.funnel.rankRejected)
        assertEquals(1,choice.rejectionDetails.missingTitleTerms)
        assertEquals(1,choice.rejectionDetails.missingArtistCredit)
        val summary=choice.rejectionDetails.summary()
        assertTrue(summary.contains("rank_missing_title=1"))
        assertTrue(summary.contains("rank_missing_artist=1"))
        assertTrue(summary.contains("rank_artist_gap=ABSENT:1"))
        assertFalse(summary.contains("Other"))
        assertFalse(summary.contains("ILLIT"))
        assertFalse(summary.contains("Magnetic"))
    }

    @Test fun noStrongCandidateReportsExclusiveGateRejectionsWithoutMetadata() {
        val samples=offers("rank",name="Other - Another.mp3")+
            offers("preflight",duration=140)+
            offers("album",album="Other Album")+
            offers("quality",bitrate=256)
        val decision=A0AutoCandidateSelectionPolicy.preview(target,samples,profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.NO_STRONG_CANDIDATE,
            decision.reason)
        assertEquals(4,decision.examined)
        assertEquals(4,decision.funnel.rankRejected+
            decision.funnel.preflightRejected+
            decision.funnel.albumRejected+
            decision.funnel.qualityRejected)
        assertEquals(1,decision.funnel.rankRejected)
        assertEquals(1,decision.funnel.preflightRejected)
        assertEquals(1,decision.funnel.albumRejected)
        assertEquals(1,decision.funnel.qualityRejected)
        val detail=decision.rejectionDetails.summary()
        assertTrue(detail.contains("album_detail=ALBUM_CLAIM_CONFLICT:1"))
        assertTrue(detail.contains("quality_detail="))
        assertTrue(detail.contains("rank_detail="))
        assertFalse(detail.contains("Other Album"))
        assertFalse(detail.contains("ILLIT"))
        assertFalse(decision.toString().contains("Other"))
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

    @Test fun equallyValidSameClaimFavorsShortQueueAndFastAdvertisedSource() {
        val slow=offers("slow",speed=70_000,queue=1)
        val fast=offers("fast",speed=4_000_000,queue=0)
        val selection=A0AutoCandidateSelectionPolicy.preview(target,slow+fast,profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,selection.status)
        assertEquals("fast",selection.sequentialAlternatives.first().peerUsername)
        assertEquals(2,selection.sequentialAlternatives.size)
        assertFalse(selection.canTransfer)
    }

    @Test fun harmlessTrackNumberPrefixWithIdenticalTechnicalClaimsDoesNotForceReview() {
        val a=offers("one")
        val b=offers("two",name="01 ILLIT - Magnetic.mp3")
        val choice=A0AutoCandidateSelectionPolicy.preview(target,a+b,profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,choice.status)
        assertEquals(2,choice.sequentialAlternatives.size)
    }

    @Test fun tiedStrongTrackNumberClaimsChooseFirstWithoutMergingConflictingGroups() {
        val first=offers("unnumbered")
        val second=offers("one",name="01 ILLIT - Magnetic.mp3")
        val third=offers("two",name="02 ILLIT - Magnetic.mp3")
        val a=A0AutoCandidateSelectionPolicy.preview(
            target,first+second+third,profile)
        val b=A0AutoCandidateSelectionPolicy.preview(
            target,second+first+third,profile)
        for(decision in listOf(a,b)) {
            assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,
                decision.status)
            assertEquals(2,decision.sequentialAlternatives.size)
            assertFalse(decision.sequentialAlternatives.any { it.peerUsername=="two" })
            assertEquals(A0AutoCandidateSelectionPolicy.TieResolution.FIRST_READY,
                decision.tieResolution)
        }
        assertEquals("unnumbered",a.sequentialAlternatives.first().peerUsername)
        assertEquals("one",b.sequentialAlternatives.first().peerUsername)
    }

    @Test fun differentSizesWithEqualRankChooseFirstReadySourceOnly() {
        val first=offers("one")
        val second=offers("two",sizeBytes=161*40_000L+1024L)
        for((candidates,expectedPeer) in listOf(
            (first+second) to "one",
            (second+first) to "two"
        )) {
            val choice=A0AutoCandidateSelectionPolicy.preview(
                target,candidates,profile)
            assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,choice.status)
            assertEquals(A0AutoCandidateSelectionPolicy.TieResolution.FIRST_READY,
                choice.tieResolution)
            assertEquals(2,choice.tieGroups)
            assertEquals(1,choice.sequentialAlternatives.size)
            assertEquals(expectedPeer,choice.sequentialAlternatives.single().peerUsername)
            assertEquals(1,choice.preByteFallbacks.size)
            assertNotEquals(expectedPeer,choice.preByteFallbacks.single().peerUsername)
            assertFalse(choice.canTransfer)
            assertFalse(choice.toString().contains(expectedPeer))
            val conservative=A0AutoCandidateSelectionPolicy.preview(
                target,candidates,profile,resolveExactTies=false)
            assertEquals(A0AutoCandidateSelectionPolicy.Reason.AMBIGUOUS_EDITION,
                conservative.reason)
        }
    }

    @Test fun tiedStrongScoresSkipCongestedOrRejectedSources() {
        val congested=offers("congested",queue=40)
        val eligible=offers("ready",sizeBytes=161*40_000L+1024L)
        val choice=A0AutoCandidateSelectionPolicy.preview(
            target,congested+eligible,profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,choice.status)
        assertEquals("ready",choice.sequentialAlternatives.first().peerUsername)
        val invalid=offers("wrong-album",album="Another Album")
        val filtered=A0AutoCandidateSelectionPolicy.preview(
            target,invalid+eligible,profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,filtered.status)
        assertEquals("ready",filtered.sequentialAlternatives.first().peerUsername)
        val truncated=A0AutoCandidateSelectionPolicy.preview(
            target,congested+eligible,profile,sampleTruncated=true)
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.INCOMPLETE_SAMPLE,
            truncated.reason)
    }

    @Test fun exactAlbumWithinOneGenericAudioSubfolderIsStillEligible() {
        for(folder in listOf(
            "FLAC","CD1","Disc 2","Lossless",
            "AAC","Opus","Ogg","Ogg Vorbis","Vorbis"
        )) {
            val decision=A0AutoCandidateSelectionPolicy.preview(
                target,offers("source",album="SUPER REAL ME\\$folder"),profile)
            assertEquals("generic folder $folder beneath exact album",
                A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,
                decision.status)
        }
    }

    @Test fun exactReleaseLabelInFilenameDoesNotFakeAnotherRecording() {
        val includingRelease=offers("album-prefixed",
            name="ILLIT - SUPER REAL ME - 01 - Magnetic.mp3")
        val picked=A0AutoCandidateSelectionPolicy.preview(
            target,includingRelease,profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,picked.status)
        val wrong=offers("wrong-folder",
            name="ILLIT - SUPER REAL ME - 01 - Magnetic.mp3",
            album="Other Album")
        assertEquals(A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,
            A0AutoCandidateSelectionPolicy.preview(target,wrong,profile).status)
    }

    @Test fun joinedArtistSpellingMustPassAllAlbumQualityAndPreflightGates() {
        val target=SoulseekBatchPlan.Entry("bp",
            "BLACK PINK - How You Like That","THE ALBUM",220)
        fun candidate(album:String):List<A0ObservedCandidateHandoff.Choice> {
            val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
                searchPhrase=target.query,requestedAlbum=target.album,
                expectedDurationSeconds=target.durationSeconds,
                qualityProfile=profile,collectReleaseShadow=true)
            collector.offerParsed(A0SoulseekSearchResponse(
                "peer",177,listOf(A0SoulseekSearchFile(
                    "Shared\\$album\\BLACKPINK - How You Like That.mp3",
                    8_800_000L,"mp3",mapOf(0 to 320,1 to 220))),
                emptyList(),true,128_000,0),"peer")
            return collector.releaseShadowChoices()
        }
        for(folder in listOf("THE ALBUM","BLACKPINK - THE ALBUM")) {
            val valid=A0AutoCandidateSelectionPolicy.preview(
                target,candidate(folder),profile)
            assertEquals(folder,A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,
                valid.status)
            assertFalse(valid.canTransfer)
        }
        for(folder in listOf("Another Album",
            "BLACKPINK - THE ALBUM [FLAC]")) {
            val conflict=A0AutoCandidateSelectionPolicy.preview(
                target,candidate(folder),profile)
            assertEquals(folder,A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,
                conflict.status)
        }
    }

    @Test fun titleOverlappingArtistUsesOnlyExactIndependentDirectoryCredit() {
        val queen=SoulseekBatchPlan.Entry("queen-hearts",
            "Queen - Queen Of Hearts","The Works",220)
        fun sample(folder:String):List<A0ObservedCandidateHandoff.Choice> {
            val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
                searchPhrase=queen.query,requestedAlbum=queen.album,
                expectedDurationSeconds=queen.durationSeconds,
                qualityProfile=profile,collectReleaseShadow=true)
            collector.offerParsed(A0SoulseekSearchResponse(
                "peer",177,listOf(A0SoulseekSearchFile(
                    "Shared\\$folder\\Queen Of Hearts.mp3",
                    8_800_000L,"mp3",mapOf(0 to 320,1 to 220))),
                emptyList(),true,128_000,0),"peer")
            return collector.releaseShadowChoices()
        }
        val exact=A0AutoCandidateSelectionPolicy.preview(
            queen,sample("Queen - The Works"),profile)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,exact.status)
        assertFalse(exact.canTransfer)
        for(folder in listOf("The Works","Queen - Different Album",
            "Queen - The Works - Remastered")) {
            val ambiguous=A0AutoCandidateSelectionPolicy.preview(
                queen,sample(folder),profile)
            assertEquals("Folder $folder must remain review-only",
                A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,ambiguous.status)
        }
    }

    @Test fun exactArtistAndAlbumFolderAllowsIndividuallyGatedAutoCandidate() {
        val offer=offers("artist-album",
            album="ILLIT - SUPER REAL ME")
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,
            A0AutoCandidateSelectionPolicy.preview(target,offer,profile).status)
        val decorated=offers("decorated",
            album="ILLIT - SUPER REAL ME [FLAC]")
        assertEquals(A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,
            A0AutoCandidateSelectionPolicy.preview(target,decorated,profile).status)
    }

    @Test fun newCodecFolderUnderWrongReleaseRemainsManual() {
        for(folder in listOf("AAC","Opus","Ogg","Ogg Vorbis","Vorbis")) {
            val selection=A0AutoCandidateSelectionPolicy.preview(
                target,offers("source",album="Other Album\\$folder"),profile)
            assertEquals(folder,A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,
                selection.status)
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
