package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.preview.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingArtistCreditBatchPreviewPlannerTest {
    private val recordingMbid = "11111111-1111-1111-1111-111111111111"
    private val artistMbid = "22222222-2222-2222-2222-222222222222"
    private fun snapshot(id: String) = RecordingArtistCreditPreviewSnapshot(
        recordingId = id,
        recordingExists = true,
        trustedRecordingMbids = listOf(recordingMbid),
        cachedRecordingMbid = recordingMbid,
        evidenceCredits = listOf(ArtistCreditEvidence(0, artistMbid, "Untrusted name", null)),
        currentCanonicalCredits = emptyList(),
        claimsByArtistMbid = mapOf(artistMbid to ArtistCreditClaim("artist-a", true, listOf(artistMbid))),
    )

    @Test fun mixedBatchIsSortedDeterministicAndExplainsSixOutcomes() {
        val inputs = listOf(
            snapshot("r6").copy(trustedRecordingMbids = listOf(recordingMbid, artistMbid)),
            snapshot("r3").copy(currentCanonicalCredits = listOf(CanonicalArtistCredit(0, "artist-b"))),
            snapshot("r5").copy(trustedRecordingMbids = emptyList()),
            snapshot("r1"),
            snapshot("r4").copy(claimsByArtistMbid = emptyMap()),
            snapshot("r2").copy(currentCanonicalCredits = listOf(CanonicalArtistCredit(0, "artist-a"))),
        )
        val result = RecordingArtistCreditBatchPreviewPlanner.plan(inputs)
        assertEquals((1..6).map { "r" + it }, result.results.map { it.recordingId })
        assertTrue(result.results[0] is RecordingArtistCreditPreview.ReadyForPromotion)
        assertTrue(result.results[1] is RecordingArtistCreditPreview.AlreadyMatches)
        assertTrue(result.results[2] is RecordingArtistCreditPreview.Conflict)
        assertTrue(result.results[3] is RecordingArtistCreditPreview.Unresolved)
        assertTrue(result.results[4] is RecordingArtistCreditPreview.NoEvidence)
        assertTrue(result.results[5] is RecordingArtistCreditPreview.IdentityConflict)
        assertEquals(BatchPreviewOutcome.values().toSet(), result.countsByOutcome.keys)
        assertTrue(result.countsByOutcome.values.all { it == 1 })
        assertEquals(1, result.countsByIssue[PreviewIssue.ARTIST_MAPPING_MISSING])
        assertEquals(1, result.countsByIssue[PreviewIssue.NO_TRUSTED_RECORDING_MBID])
        assertEquals(1, result.countsByIssue[PreviewIssue.MULTIPLE_RECORDING_MBIDS])
        assertEquals(result, RecordingArtistCreditBatchPreviewPlanner.plan(inputs.reversed()))
        assertEquals(0, RecordingArtistCreditBatchPreviewPlanner.plan(emptyList()).results.size)
    }

    @Test fun batchHasHardBoundAndRejectsBadSnapshotIdentity() {
        assertEquals(32, RecordingArtistCreditBatchPreviewPlanner.MAX_RECORDINGS)
        assertEquals(32, RecordingArtistCreditBatchPreviewPlanner.plan(
            (1..32).map { snapshot("r" + it) },
        ).results.size)
        assertThrows(IllegalArgumentException::class.java) {
            RecordingArtistCreditBatchPreviewPlanner.plan((1..33).map { snapshot("r" + it) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordingArtistCreditBatchPreviewPlanner.plan(listOf(snapshot("r1"), snapshot("r1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordingArtistCreditBatchPreviewPlanner.plan(listOf(snapshot(" ")))
        }
    }

    @Test fun missingOneCreditCannotBecomePartialReady() {
        val missing = snapshot("r").copy(evidenceCredits = listOf(
            ArtistCreditEvidence(0, artistMbid, "Mapped", " & "),
            ArtistCreditEvidence(1, "33333333-3333-3333-3333-333333333333", "Unknown", null),
        ))
        val batch = RecordingArtistCreditBatchPreviewPlanner.plan(listOf(missing))
        assertEquals(0, batch.countsByOutcome[BatchPreviewOutcome.READY_FOR_PROMOTION])
        assertEquals(1, (batch.results.single() as RecordingArtistCreditPreview.Unresolved).position)
    }
}
