package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.DownloadQualityProfile
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class A0AutoTransportAdmissionTest {
    private fun choice(extension:String):A0ObservedCandidateHandoff.Choice {
        val profile=A0SoulseekQualityProfiles.snapshot(
            DownloadQualityProfile.HIGH_QUALITY)
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",
            requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,
            qualityProfile=profile,
            collectReleaseShadow=true)
        val bytes=6_440_000L
        handoff.offerParsed(A0SoulseekSearchResponse("peer-one",177,
            listOf(A0SoulseekSearchFile(
                "Shared\\ILLIT\\SUPER REAL ME\\ILLIT - Magnetic.$extension",
                bytes,extension,mapOf(0 to 320,1 to 161))),
            emptyList(),true,128_000,0),"peer-one")
        return handoff.releaseShadowChoices().single()
    }

    @Test fun supportedReadyObservedMp3MayReachFreshAuthenticatedTransport() {
        assertTrue(A0AutoTransportAdmission.accepts(choice("mp3")))
    }

    @Test fun unsupportedSourceContainerDoesNotReachAnySavedCredentials() {
        assertFalse(A0AutoTransportAdmission.accepts(choice("opus")))
    }
}
