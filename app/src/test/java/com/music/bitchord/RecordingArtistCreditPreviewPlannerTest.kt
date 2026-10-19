package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.preview.ArtistCreditClaim
import com.music.bitchord.data.catalog.metadata.preview.ArtistCreditEvidence
import com.music.bitchord.data.catalog.metadata.preview.CanonicalArtistCredit
import com.music.bitchord.data.catalog.metadata.preview.PreviewIssue
import com.music.bitchord.data.catalog.metadata.preview.RecordingArtistCreditPreview
import com.music.bitchord.data.catalog.metadata.preview.RecordingArtistCreditPreviewPlanner
import com.music.bitchord.data.catalog.metadata.preview.RecordingArtistCreditPreviewSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingArtistCreditPreviewPlannerTest {
    private val recordingMbid = "11111111-1111-1111-1111-111111111111"
    private val artistA = "22222222-2222-2222-2222-222222222222"
    private val artistB = "33333333-3333-3333-3333-333333333333"

    private fun evidence(vararg ids: String) = ids.mapIndexed { index, mbid ->
        ArtistCreditEvidence(index, mbid, "Untrusted artist name", if (index == 0) " feat. " else null)
    }

    private fun snapshot(
        credits: List<ArtistCreditEvidence> = evidence(artistA, artistB),
        canonical: List<CanonicalArtistCredit> = emptyList(),
        claims: Map<String, ArtistCreditClaim> = mapOf(
            artistA to ArtistCreditClaim("a", true, listOf(artistA)),
            artistB to ArtistCreditClaim("b", true, listOf(artistB)),
        ),
        recordingMbids: List<String> = listOf(recordingMbid),
        cachedMbid: String? = recordingMbid,
        recordingExists: Boolean = true,
    ) = RecordingArtistCreditPreviewSnapshot(
        recordingId = "r",
        recordingExists = recordingExists,
        trustedRecordingMbids = recordingMbids,
        cachedRecordingMbid = cachedMbid,
        evidenceCredits = credits,
        currentCanonicalCredits = canonical,
        claimsByArtistMbid = claims,
    )

    @Test fun readyPlanIsExactOrderedAndPreservesCollaborations() {
        val result = RecordingArtistCreditPreviewPlanner.plan(snapshot())
        assertTrue(result is RecordingArtistCreditPreview.ReadyForPromotion)
        val proposal = (result as RecordingArtistCreditPreview.ReadyForPromotion).proposed
        assertEquals(listOf(0, 1), proposal.map { it.position })
        assertEquals(listOf("a", "b"), proposal.map { it.artistId })
        assertEquals(listOf(artistA, artistB), proposal.map { it.musicBrainzArtistId })
        assertEquals(" feat. ", proposal.first().joinPhrase)
        assertEquals("Untrusted artist name", proposal.first().evidenceName)
    }

    @Test fun repeatedArtistRemainsRepeatedWithNoDedupe() {
        val result = RecordingArtistCreditPreviewPlanner.plan(snapshot(credits = evidence(artistA, artistA, artistB)))
        assertEquals(listOf("a", "a", "b"),
            (result as RecordingArtistCreditPreview.ReadyForPromotion).proposed.map { it.artistId })
    }

    @Test fun alreadyMatchesRequiresSamePositionsAndMultiplicity() {
        val current = listOf(CanonicalArtistCredit(0, "a"), CanonicalArtistCredit(1, "b"))
        assertTrue(RecordingArtistCreditPreviewPlanner.plan(snapshot(canonical = current)) is RecordingArtistCreditPreview.AlreadyMatches)
        val reversed = current.reversed().mapIndexed { i, c -> c.copy(position = i) }
        val result = RecordingArtistCreditPreviewPlanner.plan(snapshot(canonical = reversed))
        assertTrue(result is RecordingArtistCreditPreview.Conflict)
        assertEquals(current.reversed().map { it.artistId }, (result as RecordingArtistCreditPreview.Conflict).current.map { it.artistId })
    }

    @Test fun noTrustedRecordingOrCacheOrCreditsIsNoEvidence() {
        assertEquals(PreviewIssue.NO_TRUSTED_RECORDING_MBID, (RecordingArtistCreditPreviewPlanner.plan(
            snapshot(recordingMbids = emptyList())) as RecordingArtistCreditPreview.NoEvidence).issue)
        assertEquals(PreviewIssue.NO_CACHED_RECORDING, (RecordingArtistCreditPreviewPlanner.plan(
            snapshot(cachedMbid = null)) as RecordingArtistCreditPreview.NoEvidence).issue)
        assertEquals(PreviewIssue.NO_CACHED_CREDITS, (RecordingArtistCreditPreviewPlanner.plan(
            snapshot(credits = emptyList())) as RecordingArtistCreditPreview.NoEvidence).issue)
        assertEquals(PreviewIssue.RECORDING_NOT_FOUND, (RecordingArtistCreditPreviewPlanner.plan(
            snapshot(recordingExists = false)) as RecordingArtistCreditPreview.NoEvidence).issue)
    }

    @Test fun unknownArtistMappingOrDanglingAliasNeverReturnsPartialReady() {
        val missing = RecordingArtistCreditPreviewPlanner.plan(snapshot(claims = mapOf(
            artistA to ArtistCreditClaim("a", true, listOf(artistA)),
        )))
        assertEquals(PreviewIssue.ARTIST_MAPPING_MISSING, (missing as RecordingArtistCreditPreview.Unresolved).issue)
        val dangling = RecordingArtistCreditPreviewPlanner.plan(snapshot(claims = mapOf(
            artistA to ArtistCreditClaim("a", true, listOf(artistA)),
            artistB to ArtistCreditClaim("deleted", false, listOf(artistB)),
        )))
        assertEquals(PreviewIssue.ARTIST_ENTITY_MISSING, (dangling as RecordingArtistCreditPreview.Unresolved).issue)
    }

    @Test fun divergentClaimsAndStaleCacheAreExplicitIdentityConflicts() {
        assertEquals(PreviewIssue.MULTIPLE_RECORDING_MBIDS,
            (RecordingArtistCreditPreviewPlanner.plan(snapshot(recordingMbids = listOf(recordingMbid, artistA)))
                as RecordingArtistCreditPreview.IdentityConflict).issue)
        assertEquals(PreviewIssue.CACHED_RECORDING_MBID_MISMATCH,
            (RecordingArtistCreditPreviewPlanner.plan(snapshot(cachedMbid = artistA))
                as RecordingArtistCreditPreview.IdentityConflict).issue)
        val claims = mapOf(
            artistA to ArtistCreditClaim("a", true, listOf(artistA, artistB)),
            artistB to ArtistCreditClaim("b", true, listOf(artistB)),
        )
        assertEquals(PreviewIssue.ARTIST_HAS_DIVERGENT_MBIDS,
            (RecordingArtistCreditPreviewPlanner.plan(snapshot(claims = claims))
                as RecordingArtistCreditPreview.IdentityConflict).issue)
    }

    @Test fun invalidMbidAndNoncontiguousCreditsDoNotProduceReady() {
        assertEquals(PreviewIssue.INVALID_ARTIST_MBID,
            (RecordingArtistCreditPreviewPlanner.plan(snapshot(credits = evidence("not-a-uuid")))
                as RecordingArtistCreditPreview.IdentityConflict).issue)
        assertEquals(PreviewIssue.INVALID_RECORDING_MBID,
            (RecordingArtistCreditPreviewPlanner.plan(snapshot(recordingMbids = listOf("invalid")))
                as RecordingArtistCreditPreview.IdentityConflict).issue)
        assertEquals(PreviewIssue.NONCONTIGUOUS_EVIDENCE_POSITIONS,
            (RecordingArtistCreditPreviewPlanner.plan(snapshot(credits = evidence(artistA).map { it.copy(position = 7) }))
                as RecordingArtistCreditPreview.IdentityConflict).issue)
    }

    @Test fun planIsDeterministicAndNeverUsesNameMatching() {
        val noAlias = snapshot(claims = emptyMap(), credits = listOf(
            ArtistCreditEvidence(0, artistA, "Exact same display name as canonical", null),
        ))
        val first = RecordingArtistCreditPreviewPlanner.plan(noAlias)
        assertEquals(first, RecordingArtistCreditPreviewPlanner.plan(noAlias))
        assertTrue(first is RecordingArtistCreditPreview.Unresolved)
        assertEquals(PreviewIssue.TOO_MANY_CREDITS,
            (RecordingArtistCreditPreviewPlanner.plan(snapshot(credits = evidence(*Array(65) { artistA })))
                as RecordingArtistCreditPreview.NoEvidence).issue)
    }
}
