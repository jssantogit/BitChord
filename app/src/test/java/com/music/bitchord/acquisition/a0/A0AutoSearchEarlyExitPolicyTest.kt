package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.DownloadQualityProfile
import com.music.bitchord.download.SoulseekBatchPlan
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class A0AutoSearchEarlyExitPolicyTest {
    private val quality=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
    private val tracks=listOf(
        SoulseekBatchPlan.Entry("a","ILLIT - Magnetic","SUPER REAL ME",161),
        SoulseekBatchPlan.Entry("b","ILLIT - Lucky Girl Syndrome","SUPER REAL ME",160),
        SoulseekBatchPlan.Entry("c","ILLIT - Midnight Fiction","SUPER REAL ME",166),
    )
    private fun snapshot(count:Int):A0ObservedCandidateHandoff.ReleaseShadowSnapshot {
        val handoff=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,
            searchPhrase=tracks.first().query,
            requestedAlbum="SUPER REAL ME",expectedDurationSeconds=161,
            qualityProfile=quality,collectReleaseShadow=true,
            releaseQueries=tracks.map { it.query },
            collectManualCandidates=false)
        val files=tracks.take(count).map { item ->
            A0SoulseekSearchFile(
                "Music\\ILLIT\\SUPER REAL ME\\${item.query}.mp3",
                item.durationSeconds!!.toLong()*40_000L,
                "mp3",mapOf(0 to 320,1 to item.durationSeconds))
        }
        assertTrue(handoff.offerParsed(A0SoulseekSearchResponse(
            "release-peer",177,files,emptyList(),true,128_000,0),"release-peer"))
        return handoff.releaseShadowSnapshot()
    }

    @Test fun earlyStopOnlyWhenEntireReleaseIsIndividuallyEligibleAfterEightSeconds() {
        val all=snapshot(3)
        assertFalse(A0AutoSearchEarlyExitPolicy.canFinish(7999,tracks,quality,all))
        assertTrue(A0AutoSearchEarlyExitPolicy.canFinish(8000,tracks,quality,all))
        assertFalse(A0AutoSearchEarlyExitPolicy.canFinish(
            16000,tracks,quality,snapshot(2)))
    }

    @Test fun earlyStopFailsClosedOnTruncatedPeerSampleAndSingleTrack() {
        val all=snapshot(3)
        val clipped=all.copy(truncatedSlots=setOf(1))
        assertFalse(A0AutoSearchEarlyExitPolicy.canFinish(9000,tracks,quality,clipped))
        assertFalse(A0AutoSearchEarlyExitPolicy.canFinish(
            9000,tracks,quality,all.copy(shadowDroppedAtPeerLimit=1)))
        assertFalse(A0AutoSearchEarlyExitPolicy.canFinish(
            9000,tracks.take(1),quality,all))
    }
}
