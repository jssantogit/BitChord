package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import com.music.bitchord.data.settings.DownloadQualityProfile
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class A0ReleaseCohesionPolicyTest {
    private val profile=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
    private val entries=listOf(
        SoulseekBatchPlan.Entry("one","ILLIT - Magnetic","SUPER REAL ME",161),
        SoulseekBatchPlan.Entry("two","ILLIT - Midnight Fiction","SUPER REAL ME",162),
        SoulseekBatchPlan.Entry("three","ILLIT - My World","SUPER REAL ME",163),
        SoulseekBatchPlan.Entry("four","ILLIT - Lucky Girl Syndrome","SUPER REAL ME",164),
    )
    private fun offer(
        index:Int,peer:String,folder:String="SUPER REAL ME",
        bytesExtra:Long=0,durationOffset:Int=0,
    ):A0ObservedCandidateHandoff.Choice {
        val target=entries[index]
        val title=target.query.substringAfter(" - ")
        val bridge=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase=target.query,requestedAlbum=target.album,
            expectedDurationSeconds=target.durationSeconds,
            qualityProfile=profile,collectReleaseShadow=true)
        val file=A0SoulseekSearchFile(
            "Shared\\ILLIT\\$folder\\0${index+1} ILLIT - $title.mp3",
            target.durationSeconds!!.toLong()*40_000L+bytesExtra,
            "mp3",mapOf(0 to 320,1 to (target.durationSeconds!!+durationOffset)))
        bridge.offerParsed(A0SoulseekSearchResponse(
            peer,177,listOf(file),emptyList(),true,128_000,0),peer)
        return bridge.releaseShadowChoices().single()
    }
    private fun pool(
        preferredCount:Int=4,competitorCount:Int=1,
        preferredFolder:String="SUPER REAL ME",
        competitorFolder:String="SUPER REAL ME",
        badSibling:Int=-1,
    ):List<List<A0ObservedCandidateHandoff.Choice>> =
        entries.indices.map { index ->
            buildList {
                if(index<preferredCount)
                    add(offer(index,"full",preferredFolder,
                        durationOffset=if(index==badSibling)15 else 0))
                if(index<competitorCount)
                    add(offer(index,"partial",competitorFolder,1024))
            }
        }

    @Test fun completePeerReleaseBreaksPureByteSizeTie() {
        val evidence=pool()
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.AMBIGUOUS_EDITION,
            A0AutoCandidateSelectionPolicy.preview(entries[0],evidence[0],profile).reason)
        val result=A0ReleaseCohesionPolicy.evaluate(
            entries,0,evidence,emptySet(),emptySet(),profile)
        assertEquals(A0ReleaseCohesionPolicy.Reason.UNIQUE_RELEASE_COHORT,result.reason)
        assertEquals(4,result.coverage)
        assertEquals(1,result.runnerUpCoverage)
        assertEquals(1,result.choices.size)
        assertEquals("full",result.choices.single().peerUsername)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,
            A0AutoCandidateSelectionPolicy.preview(entries[0],result.choices,profile).status)
    }
    @Test fun equallyCompletePeersNeverChooseArbitrarily() {
        val evidence=pool(4,4)
        val result=A0ReleaseCohesionPolicy.evaluate(
            entries,0,evidence,emptySet(),emptySet(),profile)
        assertEquals(A0ReleaseCohesionPolicy.Reason.TIED_COHORTS,result.reason)
        assertTrue(result.choices.isEmpty())
    }
    @Test fun incompleteReleaseAndBadSiblingCannotClaimCoverage() {
        val short=A0ReleaseCohesionPolicy.evaluate(
            entries,0,pool(2,1),emptySet(),emptySet(),profile)
        assertEquals(A0ReleaseCohesionPolicy.Reason.INSUFFICIENT_COVERAGE,short.reason)
        val bad=A0ReleaseCohesionPolicy.evaluate(
            entries,0,pool(3,1,badSibling=1),emptySet(),emptySet(),profile)
        assertEquals(A0ReleaseCohesionPolicy.Reason.INSUFFICIENT_COVERAGE,bad.reason)
    }
    @Test fun previouslyPublishedSiblingsStillCorroborateUnspentSlot() {
        val sample=pool()
        val used=setOf("full" to sample[0][0].remotePath,
            "full" to sample[1][0].remotePath)
        val result=A0ReleaseCohesionPolicy.evaluate(
            entries,2,sample,emptySet(),used,profile)
        assertEquals(A0ReleaseCohesionPolicy.Reason.UNIQUE_RELEASE_COHORT,
            result.reason)
        assertEquals(4,result.coverage)
        assertTrue(result.choices.none {
            (it.peerUsername to it.remotePath) in used })
    }

    @Test fun truncatedEvidenceAndAlreadyPublishedSourceNeverAuthorizes() {
        val sample=pool()
        val truncated=A0ReleaseCohesionPolicy.evaluate(
            entries,0,sample,setOf(2),emptySet(),profile)
        assertEquals(A0ReleaseCohesionPolicy.Reason.INCOMPLETE_SAMPLE,truncated.reason)
        val used=A0ReleaseCohesionPolicy.evaluate(
            entries,0,sample,emptySet(),
            setOf("full" to sample[0][0].remotePath),profile)
        assertTrue(used.choices.isEmpty())
    }
    @Test fun unrelatedReleaseFolderNeverBecomesCanonicalProof() {
        val wrong=A0ReleaseCohesionPolicy.evaluate(
            entries,0,pool(4,1,preferredFolder="Other Album"),
            emptySet(),emptySet(),profile)
        assertTrue(wrong.choices.isEmpty())
        val missingAlbum=entries.map { it.copy(album=null) }
        val unconstrained=A0ReleaseCohesionPolicy.evaluate(
            missingAlbum,0,pool(),emptySet(),emptySet(),profile)
        assertEquals(A0ReleaseCohesionPolicy.Reason.NO_CANONICAL_RELEASE,
            unconstrained.reason)
    }
    @Test fun loneTrackDoesNotInventReleaseCorroboration() {
        val result=A0ReleaseCohesionPolicy.evaluate(
            entries.take(1),0,pool().take(1),emptySet(),emptySet(),profile)
        assertEquals(A0ReleaseCohesionPolicy.Reason.NO_CANONICAL_RELEASE,result.reason)
    }
}
