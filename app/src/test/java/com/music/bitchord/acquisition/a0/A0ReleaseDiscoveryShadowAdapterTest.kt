package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import com.music.bitchord.data.settings.DownloadQualityProfile
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

/** Uses actual bounded A0 search replies; never calls P/F. */
class A0ReleaseDiscoveryShadowAdapterTest {
    private val profile=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
    private fun reply(vararg files:A0SoulseekSearchFile)=A0SoulseekSearchResponse(
        "peer",177,files.toList(),emptyList(),true,128000,0)
    private fun file(name:String,seconds:Int)=A0SoulseekSearchFile(
        "Shared\\ILLIT\\SUPER REAL ME\\$name.mp3",
        seconds.toLong()*40_000L,"mp3",mapOf(0 to 320,1 to seconds))
    private val entries=listOf(
        SoulseekBatchPlan.Entry("m","ILLIT - Magnetic","SUPER REAL ME",161),
        SoulseekBatchPlan.Entry("l","ILLIT - Lucky Girl Syndrome","SUPER REAL ME",160),
    )
    @Test fun onePeerResponseCoveringTwoTracksIsObservedWithoutAutoDownload() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,qualityProfile=profile)
        handoff.offerParsed(reply(
            file("ILLIT - Magnetic",161),
            file("ILLIT - Lucky Girl Syndrome",160)),"peer")
        val shadow=A0ReleaseDiscoveryShadowAdapter.preview(entries,handoff.choices(),profile)
        assertEquals(2,shadow.autoReadyTracks)
        assertEquals(2,shadow.bestFolderCoverage)
        assertFalse(shadow.canTransfer)
    }
    @Test fun unrelatedTrackAndWrongQualityCannotBoostAlbumCoverage() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,qualityProfile=profile)
        handoff.offerParsed(reply(
            file("ILLIT - Magnetic",161),
            A0SoulseekSearchFile("Shared\\ILLIT\\SUPER REAL ME\\ILLIT - Lucky Girl Syndrome.flac",
                24_000_000L,"flac",mapOf(1 to 160,4 to 44100,5 to 16))
        ),"peer")
        val shadow=A0ReleaseDiscoveryShadowAdapter.preview(entries,handoff.choices(),profile)
        assertEquals(1,shadow.autoReadyTracks)
        assertEquals(1,shadow.bestFolderCoverage)
        assertEquals(1,shadow.manualReviewTracks)
    }
}
