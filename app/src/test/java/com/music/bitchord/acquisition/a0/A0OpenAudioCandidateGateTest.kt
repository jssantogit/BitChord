package com.music.bitchord.acquisition.a0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public peer metadata is untrusted. File names and "shared" status alone never
 * establish copyright permission or source integrity.
 */
class A0OpenAudioCandidateGateTest {
    private fun vetted(
        reviewed: Boolean = true,
        sha: String = "a".repeat(64),
        source: String = "https://commons.wikimedia.org/wiki/File:Owner_licensed_tone.ogg",
        license: String = "CC0-1.0",
        bytes: Long = 4096,
    ) = A0OpenAudioCandidateGate.ReviewedReference(
        sourcePage = source,
        licenseIdentifier = license,
        expectedFilename = "Owner_licensed_tone.ogg",
        expectedSize = bytes,
        independentlyKnownSha256 = sha,
        rightsReviewedOutsideSoulseek = reviewed,
    )

    private fun candidate(
        name: String = "FreeSounds\\Owner_licensed_tone.ogg",
        bytes: Long = 4096,
        locked: Boolean = false,
        freeSlot: Boolean = true,
    ) = A0OpenAudioCandidateGate.PublicPeerListing(name, bytes, locked, freeSlot)

    @Test
    fun independentlyReviewedLicensedReferenceMayAuthorizeOneBoundedProbe() {
        val result = A0OpenAudioCandidateGate.evaluate(vetted(), candidate())
        assertTrue(result is A0OpenAudioCandidateGate.Decision.EligibleForOneProbe)
        val approved = result as A0OpenAudioCandidateGate.Decision.EligibleForOneProbe
        assertEquals(4096L, approved.expectedSize)
        assertEquals("a".repeat(64), approved.expectedSha256)
        assertEquals("CC0-1.0", approved.licenseIdentifier)
    }

    @Test
    fun neverAuthorizeOnlyBecauseRemotePeerSharesAFile() {
        val result = A0OpenAudioCandidateGate.evaluate(vetted(reviewed = false), candidate())
        assertEquals(
            A0OpenAudioCandidateGate.Rejection.RIGHTS_NOT_INDEPENDENTLY_REVIEWED,
            (result as A0OpenAudioCandidateGate.Decision.Rejected).reason,
        )
    }

    @Test
    fun noReferenceHashOrNoHttpsSourceBlocksTheProbe() {
        assertRejected(A0OpenAudioCandidateGate.Rejection.MISSING_INDEPENDENT_HASH,
            vetted(sha = ""), candidate())
        assertRejected(A0OpenAudioCandidateGate.Rejection.UNTRUSTWORTHY_SOURCE_REFERENCE,
            vetted(source = "http://commons.wikimedia.org/wiki/File:tone.ogg"), candidate())
        assertRejected(A0OpenAudioCandidateGate.Rejection.UNTRUSTWORTHY_SOURCE_REFERENCE,
            vetted(source = "https://user:pass@commons.wikimedia.org/wiki/File:tone.ogg"), candidate())
    }

    @Test
    fun misleadingLicensingOrMismatchedRemoteMetadataCannotAuthorize() {
        assertRejected(A0OpenAudioCandidateGate.Rejection.UNSUPPORTED_LICENSE,
            vetted(license = "All rights reserved"), candidate())
        assertRejected(A0OpenAudioCandidateGate.Rejection.FILENAME_MISMATCH,
            vetted(), candidate(name = "Music\\another-song.ogg"))
        assertRejected(A0OpenAudioCandidateGate.Rejection.SIZE_MISMATCH,
            vetted(), candidate(bytes = 4097L))
        assertRejected(A0OpenAudioCandidateGate.Rejection.UNSAFE_REMOTE_FILENAME,
            vetted(), candidate(name = "..\\Owner_licensed_tone.ogg"))
    }

    @Test
    fun lockedOrBusyPeersAreNotConsideredImmediateA0Proofs() {
        assertRejected(A0OpenAudioCandidateGate.Rejection.NOT_PUBLICLY_ACCESSIBLE,
            vetted(), candidate(locked = true))
        assertRejected(A0OpenAudioCandidateGate.Rejection.NO_IMMEDIATE_SLOT,
            vetted(), candidate(freeSlot = false))
    }

    @Test
    fun oversizedOrNegativePayloadNeverPassesTheProbe() {
        assertRejected(A0OpenAudioCandidateGate.Rejection.FILE_OUTSIDE_TEST_LIMIT,
            vetted(bytes = 268_435_457L), candidate(bytes = 268_435_457L))
        assertRejected(A0OpenAudioCandidateGate.Rejection.FILE_OUTSIDE_TEST_LIMIT,
            vetted(bytes = -1L), candidate(bytes = -1L))
    }

    @Test
    fun decisionDoesNotPropagateUntrustedPeerIdentityOrRemotePath() {
        val result = A0OpenAudioCandidateGate.evaluate(
            vetted(), candidate(name = "peer-secret-folder\\Owner_licensed_tone.ogg")
        )
        assertTrue(result is A0OpenAudioCandidateGate.Decision.EligibleForOneProbe)
        assertTrue(!result.toString().contains("peer-secret-folder"))
    }

    private fun assertRejected(
        reason: A0OpenAudioCandidateGate.Rejection,
        reference: A0OpenAudioCandidateGate.ReviewedReference,
        listing: A0OpenAudioCandidateGate.PublicPeerListing,
    ) {
        val result = A0OpenAudioCandidateGate.evaluate(reference, listing)
        assertTrue(result is A0OpenAudioCandidateGate.Decision.Rejected)
        assertEquals(reason, (result as A0OpenAudioCandidateGate.Decision.Rejected).reason)
    }
}
