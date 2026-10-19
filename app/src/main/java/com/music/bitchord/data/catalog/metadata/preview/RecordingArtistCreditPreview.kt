package com.music.bitchord.data.catalog.metadata.preview

import com.music.bitchord.data.catalog.CanonicalCatalogDao

/**
 * Recording-scoped, explainable preview. No result is permission to write.
 * Only trusted MusicBrainz UUID aliases may establish artist identity.
 */
internal data class ArtistCreditEvidence(
    val position: Int,
    val musicBrainzArtistId: String,
    val name: String,
    val joinPhrase: String?,
)

internal data class CanonicalArtistCredit(val position: Int, val artistId: String)

internal data class ArtistCreditClaim(
    val artistId: String?,
    val artistExists: Boolean,
    val trustedMbids: List<String>,
)

internal data class RecordingArtistCreditPreviewSnapshot(
    val recordingId: String,
    val recordingExists: Boolean,
    val trustedRecordingMbids: List<String>,
    val cachedRecordingMbid: String?,
    val evidenceCredits: List<ArtistCreditEvidence>,
    val currentCanonicalCredits: List<CanonicalArtistCredit>,
    val claimsByArtistMbid: Map<String, ArtistCreditClaim>,
)

internal data class ProposedArtistCredit(
    val position: Int,
    val artistId: String,
    val musicBrainzArtistId: String,
    val evidenceName: String,
    val joinPhrase: String?,
)

internal enum class PreviewIssue {
    RECORDING_NOT_FOUND,
    NO_TRUSTED_RECORDING_MBID,
    NO_CACHED_RECORDING,
    NO_CACHED_CREDITS,
    TOO_MANY_CREDITS,
    MULTIPLE_RECORDING_MBIDS,
    INVALID_RECORDING_MBID,
    CACHED_RECORDING_MBID_MISMATCH,
    NONCONTIGUOUS_EVIDENCE_POSITIONS,
    INVALID_ARTIST_MBID,
    ARTIST_MAPPING_MISSING,
    ARTIST_ENTITY_MISSING,
    ARTIST_HAS_DIVERGENT_MBIDS,
}

internal sealed interface RecordingArtistCreditPreview {
    val recordingId: String

    data class ReadyForPromotion(
        override val recordingId: String,
        val trustedRecordingMbid: String,
        val proposed: List<ProposedArtistCredit>,
    ) : RecordingArtistCreditPreview

    data class AlreadyMatches(
        override val recordingId: String,
        val trustedRecordingMbid: String,
        val proposed: List<ProposedArtistCredit>,
    ) : RecordingArtistCreditPreview

    data class Conflict(
        override val recordingId: String,
        val trustedRecordingMbid: String,
        val current: List<CanonicalArtistCredit>,
        val proposed: List<ProposedArtistCredit>,
    ) : RecordingArtistCreditPreview

    data class NoEvidence(
        override val recordingId: String,
        val issue: PreviewIssue,
    ) : RecordingArtistCreditPreview

    data class Unresolved(
        override val recordingId: String,
        val issue: PreviewIssue,
        val position: Int,
        val musicBrainzArtistId: String,
    ) : RecordingArtistCreditPreview

    data class IdentityConflict(
        override val recordingId: String,
        val issue: PreviewIssue,
        val position: Int? = null,
        val musicBrainzArtistId: String? = null,
    ) : RecordingArtistCreditPreview
}

/** Pure and deterministic: accepts a snapshot, never queries networks or mutates state. */
internal object RecordingArtistCreditPreviewPlanner {
    private const val MAX_CREDITS = 64
    private val musicBrainzUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun plan(snapshot: RecordingArtistCreditPreviewSnapshot): RecordingArtistCreditPreview {
        val recordingId = snapshot.recordingId
        fun noEvidence(issue: PreviewIssue) = RecordingArtistCreditPreview.NoEvidence(recordingId, issue)
        fun conflict(issue: PreviewIssue, credit: ArtistCreditEvidence? = null) =
            RecordingArtistCreditPreview.IdentityConflict(
                recordingId, issue, credit?.position, credit?.musicBrainzArtistId,
            )

        if (!snapshot.recordingExists) return noEvidence(PreviewIssue.RECORDING_NOT_FOUND)
        val trusted = snapshot.trustedRecordingMbids
        if (trusted.isEmpty()) return noEvidence(PreviewIssue.NO_TRUSTED_RECORDING_MBID)
        if (trusted.size != 1) return conflict(PreviewIssue.MULTIPLE_RECORDING_MBIDS)
        val trustedMbid = trusted.single()
        if (!musicBrainzUuid.matches(trustedMbid)) return conflict(PreviewIssue.INVALID_RECORDING_MBID)
        val cached = snapshot.cachedRecordingMbid ?: return noEvidence(PreviewIssue.NO_CACHED_RECORDING)
        if (!musicBrainzUuid.matches(cached) || !cached.equals(trustedMbid, ignoreCase = true)) {
            return conflict(PreviewIssue.CACHED_RECORDING_MBID_MISMATCH)
        }

        val evidence = snapshot.evidenceCredits.sortedWith(compareBy(ArtistCreditEvidence::position, ArtistCreditEvidence::musicBrainzArtistId))
        if (evidence.isEmpty()) return noEvidence(PreviewIssue.NO_CACHED_CREDITS)
        if (evidence.size > MAX_CREDITS) return noEvidence(PreviewIssue.TOO_MANY_CREDITS)
        if (evidence.indices.any { evidence[it].position != it }) {
            return conflict(PreviewIssue.NONCONTIGUOUS_EVIDENCE_POSITIONS)
        }

        val proposed = ArrayList<ProposedArtistCredit>(evidence.size)
        for (credit in evidence) {
            val mbid = credit.musicBrainzArtistId
            if (!musicBrainzUuid.matches(mbid)) return conflict(PreviewIssue.INVALID_ARTIST_MBID, credit)
            val claim = snapshot.claimsByArtistMbid[mbid]
            if (claim?.artistId == null) {
                return RecordingArtistCreditPreview.Unresolved(
                    recordingId, PreviewIssue.ARTIST_MAPPING_MISSING, credit.position, mbid,
                )
            }
            if (!claim.artistExists) {
                return RecordingArtistCreditPreview.Unresolved(
                    recordingId, PreviewIssue.ARTIST_ENTITY_MISSING, credit.position, mbid,
                )
            }
            if (claim.trustedMbids.size != 1 ||
                !claim.trustedMbids.single().equals(mbid, ignoreCase = true)
            ) {
                return conflict(PreviewIssue.ARTIST_HAS_DIVERGENT_MBIDS, credit)
            }
            proposed += ProposedArtistCredit(
                position = credit.position,
                artistId = claim.artistId,
                musicBrainzArtistId = mbid,
                evidenceName = credit.name,
                joinPhrase = credit.joinPhrase,
            )
        }

        val canonical = snapshot.currentCanonicalCredits.sortedWith(
            compareBy(CanonicalArtistCredit::position, CanonicalArtistCredit::artistId),
        )
        return when {
            canonical.isEmpty() ->
                RecordingArtistCreditPreview.ReadyForPromotion(recordingId, trustedMbid, proposed)
            canonical == proposed.map { CanonicalArtistCredit(it.position, it.artistId) } ->
                RecordingArtistCreditPreview.AlreadyMatches(recordingId, trustedMbid, proposed)
            else ->
                RecordingArtistCreditPreview.Conflict(recordingId, trustedMbid, canonical, proposed)
        }
    }
}

/**
 * Provider-neutral read entry point. Snapshot collection is one Room transaction,
 * and the planner performs no SQL or mutation.
 */
internal class RecordingArtistCreditPreviewRepository(
    private val dao: CanonicalCatalogDao,
) {
    suspend fun preview(recordingId: String): RecordingArtistCreditPreview =
        RecordingArtistCreditPreviewPlanner.plan(dao.recordingArtistCreditPreviewSnapshot(recordingId))
}
