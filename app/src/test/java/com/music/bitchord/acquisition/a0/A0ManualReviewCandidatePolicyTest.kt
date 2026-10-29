package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import com.music.bitchord.data.settings.DownloadQualityProfile
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class A0ManualReviewCandidatePolicyTest {
    private val profile=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
    private val target=SoulseekBatchPlan.Entry(
        "magnetic","ILLIT - Magnetic","SUPER REAL ME",161)

    private fun offer(peer:String,track:String,bitrate:Int=320,seconds:Int=161):
        A0ObservedCandidateHandoff.Choice {
        val handoff=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,
            searchPhrase=target.query,requestedAlbum=target.album,
            expectedDurationSeconds=161,qualityProfile=profile,
            collectReleaseShadow=true)
        val file=A0SoulseekSearchFile(
            "Music\\\\ILLIT\\\\SUPER REAL ME\\\\ILLIT - $track.mp3",
            seconds.toLong()*40_000,"mp3",mapOf(0 to bitrate,1 to seconds))
        handoff.offerParsed(A0SoulseekSearchResponse(
            peer,177,listOf(file),emptyList(),true,128_000,0),peer)
        return handoff.releaseShadowChoices().single()
    }

    @Test fun ambiguousGoodOffersRetainedForHumanInsteadOfAnotherSearch() {
        val all=listOf(offer("a","Magnetic"),offer("b","Magnetic"))
        val found=A0ManualReviewCandidatePolicy.retain(target,all,profile)
        assertEquals(2,found.size)
        assertTrue(found.containsAll(all))
    }

    @Test fun wrongSongAndBelowRequestedQualityNeverEnterReviewTransferPicker() {
        val list=listOf(offer("good","Magnetic"),
            offer("wrong","Other Track"),offer("low","Magnetic",128))
        val result=A0ManualReviewCandidatePolicy.retain(target,list,profile)
        assertEquals(listOf(list[0]),result)
    }

    @Test fun largeDiscoveryRetainsAtMostSixteenWithoutSavingPeerIdsToString() {
        val choices=(0 until 80).map { offer("peer-$it","Magnetic") }
        val result=A0ManualReviewCandidatePolicy.retain(target,choices,profile)
        assertEquals(16,result.size)
        assertFalse(A0ManualReviewCandidatePolicy.toString().contains("peer-0"))
    }
}
