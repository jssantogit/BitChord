package com.music.bitchord.acquisition.a0

/**
 * Chooses which already-ranked peer *claims* appear in the default manual
 * selection list. It is not an auto-selection policy or an authorization gate.
 *
 * Quality compatibility alone is insufficient: a 320 kbps file explicitly
 * claiming a conflicting artist, album or version must not be promoted to the
 * primary list. Unknown album/edition evidence can remain for manual review.
 * All bounded choices remain available through the separate inspection action.
 */
internal object A0ManualCandidatePolicy {
    fun primaryIndices(
        choices: List<A0ObservedCandidateHandoff.Choice>,
        selectableIndices: List<Int>,
    ): List<Int> = selectableIndices.filter { index ->
        val claim = choices.getOrNull(index) ?: return@filter false
        claim.qualityEvidence.status ==
            A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED &&
            claim.preflight.confidence != A0CandidatePreflight.Confidence.UNSAFE &&
            claim.identityEvidence.verdict != A0CanonicalIdentityPolicy.Verdict.INCOMPATIBLE &&
            claim.albumEvidence.status != A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM
    }
}
