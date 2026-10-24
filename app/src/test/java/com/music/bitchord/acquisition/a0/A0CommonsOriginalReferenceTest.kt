package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/**
 * Immutable source proof, independently computed by GitHub Actions on Commons
 * ORIGINAL bytes: A0 CC0 Original Reference Proof #39519675488.
 *
 * This does NOT prove a remote peer carries the same bytes. Search metadata
 * is untrusted; a future bounded transfer still needs exact final SHA-256.
 */
class A0CommonsOriginalReferenceTest {
    @Test fun immutableOriginalManifestHasAuthoritativeSourceExactSizeAndSha256() {
        val source = A0CommonsOriginalReference.reviewedOriginal()
        assertEquals("https://commons.wikimedia.org/wiki/File:Ringingsound.wav", source.sourcePage)
        assertEquals("CC0-1.0", source.licenseIdentifier)
        assertEquals("Ringingsound.wav", source.expectedFilename)
        assertEquals(27758L, source.expectedSize)
        assertEquals(
            "44254413b25cfffc3cc1e0d7c6e24aeb88cf99b867ff4e8b4088524fe0fb8325",
            source.independentlyKnownSha256
        )
        assertTrue(source.rightsReviewedOutsideSoulseek)
    }

    @Test fun onlyExactCandidateMayPassGateButNarutoProbeCannot() {
        val ref = A0CommonsOriginalReference.reviewedOriginal()
        val exact = A0OpenAudioCandidateGate.PublicPeerListing(
            "Public\\Ringingsound.wav", 27758L, locked = false, freeSlot = true
        )
        val result = A0OpenAudioCandidateGate.evaluate(ref, exact)
        assertTrue(result is A0OpenAudioCandidateGate.Decision.EligibleForOneProbe)
        for (unsafe in listOf(
            exact.copy(remoteFilename="anime\\naruto-theme.wav"),
            exact.copy(reportedSize=27757L),
            exact.copy(reportedSize=27759L),
            exact.copy(locked=true),
            exact.copy(freeSlot=false),
            exact.copy(remoteFilename="../Ringingsound.wav")
        )) {
            assertTrue(
                A0OpenAudioCandidateGate.evaluate(ref, unsafe)
                    is A0OpenAudioCandidateGate.Decision.Rejected
            )
        }
    }
}
