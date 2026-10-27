package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

/** Main-engine policy used by the devDebug manual chooser; no P/F authority. */
class A0ManualCandidatePolicyTest {
    private fun offer(path:String, format:String="mp3", bitrate:Int=320) =
        A0SoulseekSearchFile(path, 6_400_000L, format, mapOf(0 to bitrate, 1 to 161))
    private fun result(peer:String, vararg files:A0SoulseekSearchFile) =
        A0SoulseekSearchResponse(peer, 177, files.toList(), emptyList(), true, 250_000, 0)

    @Test fun studioClaimRanksBeforeWrongAlbumAndWrongVersionWhileAllStayInspectable() {
        val choices=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,
            qualityProfile=A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                preset=A0CanonicalAcquisitionIntent.QualityPreset.HIGH_QUALITY, strict=true
            )
        )
        choices.offerParsed(result("wrong-album",
            offer("Music\\ILLIT\\Greatest Hits\\ILLIT Magnetic.mp3")), "wrong-album")
        choices.offerParsed(result("live",
            offer("Music\\ILLIT\\SUPER REAL ME\\ILLIT Magnetic live.mp3")), "live")
        choices.offerParsed(result("studio",
            offer("Music\\ILLIT\\SUPER REAL ME\\ILLIT Magnetic.mp3")), "studio")
        choices.offerParsed(result("uncertain",
            offer("Music\\Downloads\\ILLIT Magnetic.mp3")), "uncertain")
        choices.offerParsed(result("wrong-quality",
            offer("Music\\ILLIT\\SUPER REAL ME\\ILLIT Magnetic.flac", "flac", 0)), "wrong-quality")

        val all=choices.choices()
        assertEquals(5,all.size)
        val primary=A0ManualCandidatePolicy.primaryIndices(all,all.indices.toList())
        assertEquals(2,primary.size)
        assertEquals("studio",all[primary[0]].peerUsername)
        assertEquals("uncertain",all[primary[1]].peerUsername)
        assertTrue(primary.none { all[it].peerUsername=="wrong-album" })
        assertTrue(primary.none { all[it].peerUsername=="live" })
        assertTrue(primary.none { all[it].peerUsername=="wrong-quality" })
        // This policy must not discard alternatives or authorize transfers.
        assertEquals(5,choices.choices().size)
        assertTrue(all.all { !it.identityEvidence.authorizesTransfer })
    }

    @Test fun knownBadMatchesNeverBecomePrimaryJustBecauseTheyAreMp3ThreeTwenty() {
        val handoff=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161,
            qualityProfile=A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                preset=A0CanonicalAcquisitionIntent.QualityPreset.HIGH_QUALITY,strict=true)
        )
        handoff.offerParsed(result("remix",
            offer("Music\\ILLIT\\ILLIT Magnetic remix.mp3")), "remix")
        val all=handoff.choices()
        assertEquals(1,all.size)
        assertTrue(A0ManualCandidatePolicy.primaryIndices(all,listOf(0)).isEmpty())
        assertEquals(1,all.size)
    }
    @Test fun unconstrainedLegacyProfilePreservesOrdinaryManualCandidates() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        handoff.offerParsed(result("source",
            offer("Music\\ILLIT\\ILLIT Magnetic.mp3")), "source")
        val all=handoff.choices()
        assertEquals(A0DownloadQualityPolicy.Status.NOT_CONSTRAINED,
            all.single().qualityEvidence.status)
        assertEquals(listOf(0),A0ManualCandidatePolicy.primaryIndices(all,listOf(0)))
    }


    @Test fun compositeReleaseFolderIsVisibleOnlyAsInconclusiveManualChoice() {
        val handoff=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,
            searchPhrase="ILLIT - More",requestedAlbum="BREAK EVEN",
            expectedDurationSeconds=183,collectReleaseShadow=true,
            qualityProfile=A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                preset=A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS,strict=true)
        )
        val file=A0SoulseekSearchFile(
            "Music\\ILLIT - BREAK EVEN [FLAC]\\ILLIT - More.flac",
            24_000_000L,"flac",mapOf(1 to 183,4 to 44_100,5 to 16))
        val wrong=A0SoulseekSearchFile(
            "Music\\Greatest Hits\\ILLIT - More.flac",
            24_000_000L,"flac",mapOf(1 to 183,4 to 44_100,5 to 16))
        handoff.offerParsed(result("composite",file),"composite")
        handoff.offerParsed(result("wrong",wrong),"wrong")
        val choices=handoff.choices()
        val indices=A0ManualCandidatePolicy.primaryIndices(choices,choices.indices.toList())
        assertEquals(2,choices.size)
        assertEquals(1,indices.size)
        val visible=choices[indices.single()]
        assertEquals("composite",visible.peerUsername)
        assertEquals(A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,
            visible.albumEvidence.status)
        assertFalse(visible.identityEvidence.authorizesTransfer)
        assertEquals(A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS,
            A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS)
        assertEquals(2,handoff.releaseShadowChoices().size)
    }
}
