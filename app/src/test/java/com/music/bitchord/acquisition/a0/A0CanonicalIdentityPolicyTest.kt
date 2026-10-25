package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Identity verdicts must not be compensated by bitrate or peer availability. */
class A0CanonicalIdentityPolicyTest {
    private val exact = A0CanonicalAcquisitionResolver.Resolution(
        A0CanonicalAcquisitionResolver.Status.CONFIRMED_RELATION
    )
    private val unavailable = A0CanonicalAcquisitionResolver.Resolution(
        A0CanonicalAcquisitionResolver.Status.UNAVAILABLE
    )
    private fun request(
        album: String? = "Blue Sky",
        exactEdition: Boolean = false,
        version: Set<A0CanonicalAcquisitionIntent.VersionTag> = emptySet()
    ): A0CanonicalAcquisitionIntent =
        if(exactEdition) A0CanonicalAcquisitionIntent.confirmedCatalog(
            title="Blue Sky", requestedAlbum=album, strictExactRelease=true,
            ids=A0CanonicalAcquisitionIntent.CanonicalIds(
                recordingMbid="11111111-1111-4111-8111-111111111111",
                releaseMbid="22222222-2222-4222-8222-222222222222"
            ),
            requestedVersion=version
        ) else A0CanonicalAcquisitionIntent.manual(
            title="Blue Sky", artistCredits=listOf("Mira"),
            requestedAlbum=album, requestedVersion=version
        )
    private val strong=A0CandidatePreflight.Assessment(
        A0CandidatePreflight.Confidence.STRONG, emptyList()
    )
    private val review=A0CandidatePreflight.Assessment(
        A0CandidatePreflight.Confidence.REVIEW,
        listOf(A0CandidatePreflight.Reason.DURATION_UNCONFIRMED)
    )

    @Test fun conflictingAlbumClaimIsIncompatibleEvenWithExcellentTechnicalScore() {
        val decision=A0CanonicalIdentityPolicy.assess(
            request(), exact, A0CanonicalAlbumPolicy.Claim("Greatest Hits"), strong
        )
        assertEquals(A0CanonicalIdentityPolicy.Verdict.INCOMPATIBLE, decision.verdict)
        assertTrue(A0CanonicalIdentityPolicy.Reason.ALBUM_CONFLICT in decision.reasons)
        assertFalse(decision.authorizesTransfer)
    }

    @Test fun exactReleaseIsNeverAuthenticatedByMatchingFolderOrCatalogueTrackRelationship() {
        val decision=A0CanonicalIdentityPolicy.assess(
            request(exactEdition=true), exact,
            A0CanonicalAlbumPolicy.Claim("Blue Sky"), strong
        )
        assertEquals(A0CanonicalIdentityPolicy.Verdict.INCONCLUSIVE_MANUAL, decision.verdict)
        assertTrue(A0CanonicalIdentityPolicy.Reason.EXACT_FILE_EDITION_UNVERIFIED in decision.reasons)
    }

    @Test fun unknownAlbumPreservesManualCandidateInsteadOfPromotingIt() {
        val decision=A0CanonicalIdentityPolicy.assess(
            request(), exact, A0CanonicalAlbumPolicy.Claim(null), strong
        )
        assertEquals(A0CanonicalIdentityPolicy.Verdict.INCONCLUSIVE_MANUAL, decision.verdict)
        assertFalse(decision.authorizesTransfer)
    }

    @Test fun canonicalOutageIsInconclusiveNotWrongMusic() {
        val decision=A0CanonicalIdentityPolicy.assess(
            request(), unavailable, A0CanonicalAlbumPolicy.Claim("Blue Sky"), strong
        )
        assertEquals(A0CanonicalIdentityPolicy.Verdict.INCONCLUSIVE_MANUAL, decision.verdict)
        assertTrue(A0CanonicalIdentityPolicy.Reason.CANONICAL_UNAVAILABLE in decision.reasons)
    }

    @Test fun technicalOrIdentitySafetyVetoBeatsAnAlbumNameMatch() {
        val unsafe=A0CandidatePreflight.Assessment(
            A0CandidatePreflight.Confidence.UNSAFE,
            listOf(A0CandidatePreflight.Reason.ENCODING_CONFLICT)
        )
        val decision=A0CanonicalIdentityPolicy.assess(
            request(), exact, A0CanonicalAlbumPolicy.Claim("Blue Sky"), unsafe
        )
        assertEquals(A0CanonicalIdentityPolicy.Verdict.INCOMPATIBLE, decision.verdict)
        assertTrue(A0CanonicalIdentityPolicy.Reason.PREFLIGHT_CONFLICT in decision.reasons)
    }

    @Test fun requestedRemixCannotBeStrongWhenPeerVersionClaimIsUnknown() {
        val decision=A0CanonicalIdentityPolicy.assess(
            request(version=setOf(A0CanonicalAcquisitionIntent.VersionTag.REMIX)),
            exact, A0CanonicalAlbumPolicy.Claim("Blue Sky"), strong,
            observedVersionTags=emptySet()
        )
        assertEquals(A0CanonicalIdentityPolicy.Verdict.INCONCLUSIVE_MANUAL, decision.verdict)
    }

    @Test fun explicitlyIncompatibleVersionTagCannotBePromoted() {
        val decision=A0CanonicalIdentityPolicy.assess(
            request(), exact, A0CanonicalAlbumPolicy.Claim("Blue Sky"), strong,
            observedVersionTags=setOf(A0CanonicalAcquisitionIntent.VersionTag.REMASTER)
        )
        assertEquals(A0CanonicalIdentityPolicy.Verdict.INCOMPATIBLE, decision.verdict)
        assertTrue(A0CanonicalIdentityPolicy.Reason.VERSION_CONFLICT in decision.reasons)
    }

    @Test fun adequateIndependentEvidenceCanBeStrongButNeverVerifiedAudio() {
        val decision=A0CanonicalIdentityPolicy.assess(
            request(), exact, A0CanonicalAlbumPolicy.Claim("Blue Sky"), strong
        )
        assertEquals(A0CanonicalIdentityPolicy.Verdict.COMPATIBLE_STRONG_UNVERIFIED, decision.verdict)
        assertFalse(decision.provesAudioMaster)
        assertFalse(decision.authorizesTransfer)
    }

    @Test fun preflightReviewNeverBecomesStrongDueToCatalogueEvidence() {
        val decision=A0CanonicalIdentityPolicy.assess(
            request(), exact, A0CanonicalAlbumPolicy.Claim("Blue Sky"), review
        )
        assertEquals(A0CanonicalIdentityPolicy.Verdict.INCONCLUSIVE_MANUAL, decision.verdict)
    }
}
