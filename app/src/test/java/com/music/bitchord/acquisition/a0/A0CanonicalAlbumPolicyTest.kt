package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Album/edition decisions are made from *untrusted* metadata, not file audio. */
class A0CanonicalAlbumPolicyTest {
    private val policy=A0CanonicalAlbumPolicy

    @Test fun conflictingAdvertisedAlbumCannotBePromotedByExcellentEncoding() {
        val mismatch=policy.assess(
            target=A0CanonicalAlbumPolicy.Target("Blue Sky"),
            claim=A0CanonicalAlbumPolicy.Claim("Greatest Hits")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,mismatch.status)
        assertEquals(A0CanonicalAlbumPolicy.Reason.ALBUM_CLAIM_CONFLICT,mismatch.reason)
    }

    @Test fun exactlyMatchingAlbumNameIsOnlyAnUnverifiedClaimNotVerifiedMaster() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky"),
            A0CanonicalAlbumPolicy.Claim("Blue Sky")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,result.status)
        assertNotEquals(A0CanonicalAlbumPolicy.Status.VERIFIED_EDITION,result.status)
    }

    @Test fun missingAlbumEvidencePreservesManualCandidateWithoutPromotingIt() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky"),
            A0CanonicalAlbumPolicy.Claim(null)
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,result.status)
        assertEquals(A0CanonicalAlbumPolicy.Reason.ALBUM_NOT_ADVERTISED,result.reason)
    }

    @Test fun exactReleaseIdCannotBeProvedFromMatchingPeerAlbumText() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target(
                requestedAlbum="Blue Sky",
                exactReleaseMbid="11111111-1111-4111-8111-111111111111"
            ),
            A0CanonicalAlbumPolicy.Claim("Blue Sky")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,result.status)
        assertEquals(A0CanonicalAlbumPolicy.Reason.EXACT_RELEASE_NOT_PROVEN,result.reason)
    }

    @Test fun anUnconstrainedAlbumNeverFabricatesAlbumEvidence() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target(null),
            A0CanonicalAlbumPolicy.Claim("Another Album")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.UNCONSTRAINED,result.status)
    }

    @Test fun releaseSuffixMustNotPassAnExactAlbumNameMatch() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky"),
            A0CanonicalAlbumPolicy.Claim("Blue Sky Remastered 2021")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,result.status)
    }

    @Test fun caseAccentAndPunctuationDifferencesNormalizeWithoutFuzzySubstrings() {
        val good=policy.assess(
            A0CanonicalAlbumPolicy.Target("Coração, A"),
            A0CanonicalAlbumPolicy.Claim(" coracao a ")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,good.status)
        val bad=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky"),
            A0CanonicalAlbumPolicy.Claim("Sky")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,bad.status)
    }

    @Test fun albumClaimIsNeverTransferAuthorization() {
        val data=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky"),
            A0CanonicalAlbumPolicy.Claim("Blue Sky")
        )
        assertFalse(data.authorizesTransfer)
    }
}
