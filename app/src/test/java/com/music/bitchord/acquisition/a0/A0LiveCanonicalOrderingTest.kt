package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

/** Actual devDebug candidate collector ordering, not just an offline scorer. */
class A0LiveCanonicalOrderingTest {
    private fun offer(path:String, format:String="mp3", bitrate:Int=320)=
        A0SoulseekSearchFile(path,6L*1024L*1024L,format,
            mapOf(0 to bitrate,1 to 180))
    private fun reply(peer:String,vararg offers:A0SoulseekSearchFile)=
        A0SoulseekSearchResponse(peer,177,offers.toList(),
            emptyList(),true,300_000,0)

    @Test fun desiredAlbumPrecedesHigherBitrateWrongCompilationInLiveManualList() {
        val collector=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,searchPhrase="Mira - Blue Sky",
            requestedAlbum="Blue Sky"
        )
        collector.offerParsed(reply("peerA",
            offer("Music\\Mira\\Greatest Hits\\Mira - Blue Sky.mp3")),"peerA")
        collector.offerParsed(reply("peerB",
            offer("Music\\Mira\\Blue Sky\\Mira - Blue Sky.flac","flac",0)),"peerB")
        val choices=collector.choices()
        assertEquals(2,choices.size)
        assertTrue(choices.first().displayName.endsWith(".flac"))
        assertEquals(A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,
            choices.first().albumEvidence.status)
        assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,
            choices.last().albumEvidence.status)
        assertTrue(choices.all { !it.identityEvidence.authorizesTransfer })
    }

    @Test fun genericFolderIsUncertainNotWrongAlbumAndNeverDropped() {
        val collector=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,searchPhrase="Mira - Blue Sky",
            requestedAlbum="Blue Sky"
        )
        collector.offerParsed(reply("peer",
            offer("Music\\Downloads\\Mira - Blue Sky.mp3")),"peer")
        val choice=collector.choices().single()
        assertEquals(A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,
            choice.albumEvidence.status)
        assertEquals(A0CanonicalIdentityPolicy.Verdict.INCONCLUSIVE_MANUAL,
            choice.identityEvidence.verdict)
    }

    @Test fun absenceOfExplicitAlbumPreservesLegacyLiveSorting() {
        val collector=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,searchPhrase="Mira - Blue Sky"
        )
        collector.offerParsed(reply("peerA",
            offer("Music\\Mira\\Greatest Hits\\Mira - Blue Sky.mp3")),"peerA")
        collector.offerParsed(reply("peerB",
            offer("Music\\Mira\\Blue Sky\\Mira - Blue Sky.mp3",bitrate=128)),"peerB")
        assertEquals(2,collector.choices().size)
        assertEquals(A0CanonicalAlbumPolicy.Status.UNCONSTRAINED,
            collector.choices().first().albumEvidence.status)
    }

    @Test fun explicitLosslessPreferenceRanksLosslessAheadOfLossyWithoutHidingIt() {
        val profile=A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
            preset=A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS,
            strict=true,
        )
        val collector=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,searchPhrase="Mira - Blue Sky",
            qualityProfile=profile
        )
        collector.offerParsed(reply("peer",
            offer("Music\\Mira - Blue Sky.mp3"),
            offer("Music\\Mira - Blue Sky.flac","flac",0)
        ),"peer")
        val choices=collector.choices()
        assertEquals(2,choices.size)
        assertEquals("flac",choices.first().displayName.substringAfterLast('.'))
        assertEquals(A0DownloadQualityPolicy.Status.INCOMPATIBLE,
            choices.last().qualityEvidence.status)
    }

    @Test fun reasonOnlyCollectorNeverChangesExplicitTransferIndexOrStartsNetwork() {
        val collector=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,searchPhrase="Mira - Blue Sky",
            requestedAlbum="Blue Sky"
        )
        collector.offerParsed(reply("peer",
            offer("Music\\Greatest Hits\\Mira - Blue Sky.mp3")),"peer")
        assertEquals(1,collector.choices().size)
        assertEquals(1,collector.screening().accepted)
        assertFalse(collector.choices().single().identityEvidence.authorizesTransfer)
        collector.cancel()
        assertTrue(collector.choices().isEmpty())
    }
}
