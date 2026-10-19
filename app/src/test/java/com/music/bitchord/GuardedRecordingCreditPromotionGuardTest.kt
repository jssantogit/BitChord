package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.promotion.CreditPromotionGate
import com.music.bitchord.data.catalog.metadata.promotion.RecordingCreditPromotionGuard
import com.music.bitchord.data.catalog.metadata.preview.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedRecordingCreditPromotionGuardTest {
    private val recordingMbid = "11111111-1111-1111-1111-111111111111"
    private val artistMbid = "22222222-2222-2222-2222-222222222222"

    private fun snapshot() = RecordingArtistCreditPreviewSnapshot(
        recordingId = "r1",
        recordingExists = true,
        trustedRecordingMbids = listOf(recordingMbid),
        cachedRecordingMbid = recordingMbid,
        evidenceCredits = listOf(
            ArtistCreditEvidence(0, artistMbid, "Provider display is not canonical", " & "),
            ArtistCreditEvidence(1, artistMbid, "Again", null),
        ),
        currentCanonicalCredits = emptyList(),
        claimsByArtistMbid = mapOf(artistMbid to ArtistCreditClaim("artist-a", true, listOf(artistMbid))),
    )
    private fun ready(): RecordingArtistCreditPreview.ReadyForPromotion =
        RecordingArtistCreditPreviewPlanner.plan(snapshot()) as RecordingArtistCreditPreview.ReadyForPromotion

    @Test fun exactFreshReadyProducesInsertWithOrderedMultiplicity() {
        val expected = ready()
        val gate = RecordingCreditPromotionGuard.evaluate(expected, expected)
        assertTrue(gate is CreditPromotionGate.Insert)
        assertEquals(listOf("artist-a", "artist-a"),
            (gate as CreditPromotionGate.Insert).credits.map { it.artistId })
        assertEquals(listOf(0, 1), gate.credits.map { it.position })
        assertEquals("r1", gate.credits.first().recordingId)
    }

    @Test fun repeatedApprovedRequestIsIdempotentAfterMatch() {
        val expected = ready()
        val live = RecordingArtistCreditPreviewPlanner.plan(
            snapshot().copy(currentCanonicalCredits = listOf(
                CanonicalArtistCredit(0, "artist-a"), CanonicalArtistCredit(1, "artist-a"),
            )))
        assertTrue(RecordingCreditPromotionGuard.evaluate(expected, live) is CreditPromotionGate.AlreadyApplied)
    }

    @Test fun forgedOrStaleReadinessAndExistingDifferentCreditsFailClosed() {
        val expected = ready()
        val forged = expected.copy(proposed = expected.proposed.reversed())
        assertTrue(RecordingCreditPromotionGuard.evaluate(forged, expected) is CreditPromotionGate.Rejected)
        val changedEvidence = expected.copy(proposed = expected.proposed.map {
            it.copy(evidenceName = "New provider evidence")
        })
        assertTrue(RecordingCreditPromotionGuard.evaluate(expected, changedEvidence) is CreditPromotionGate.Rejected)
        val conflict = RecordingArtistCreditPreviewPlanner.plan(
            snapshot().copy(currentCanonicalCredits = listOf(CanonicalArtistCredit(0, "other"))))
        val rejected = RecordingCreditPromotionGuard.evaluate(expected, conflict)
        assertTrue(rejected is CreditPromotionGate.Rejected)
        assertTrue((rejected as CreditPromotionGate.Rejected).latest is RecordingArtistCreditPreview.Conflict)
    }

    @Test fun missingClaimsAndIdentityConflictAlwaysBlockPromotion() {
        val expected = ready()
        val unresolved = RecordingArtistCreditPreviewPlanner.plan(snapshot().copy(claimsByArtistMbid = emptyMap()))
        val identityConflict = RecordingArtistCreditPreviewPlanner.plan(
            snapshot().copy(trustedRecordingMbids = listOf(recordingMbid, artistMbid)))
        assertTrue(RecordingCreditPromotionGuard.evaluate(expected, unresolved) is CreditPromotionGate.Rejected)
        assertTrue(RecordingCreditPromotionGuard.evaluate(expected, identityConflict) is CreditPromotionGate.Rejected)
    }
}
