package com.music.bitchord.acquisition.a0

/**
 * Conservative identity-first assessment with no transport, DB or UI access.
 * Peer fields are untrusted claims. Even a confirmed MusicBrainz relationship
 * NEVER authenticates actual bytes, mastering, codecs or edition of a file.
 *
 * The existing devDebug chooser remains the only transfer-initiating path.
 * This policy is an isolated canonical bridge until #178 integration is proven.
 */
internal object A0CanonicalIdentityPolicy {
    enum class Verdict {
        COMPATIBLE_STRONG_UNVERIFIED, INCONCLUSIVE_MANUAL, INCOMPATIBLE,
    }
    enum class Reason {
        PREFLIGHT_CONFLICT, PREFLIGHT_INSUFFICIENT,
        ALBUM_CONFLICT, ALBUM_UNCONFIRMED, VERSION_CONFLICT, VERSION_UNCONFIRMED,
        CANONICAL_UNAVAILABLE, CANONICAL_AMBIGUOUS, CANONICAL_CONFLICT,
        CANONICAL_INCOMPLETE, EXACT_FILE_EDITION_UNVERIFIED,
    }
    class Decision(val verdict: Verdict, reasons: Set<Reason>) {
        val reasons: Set<Reason> = reasons.toSet()
        val provesAudioMaster: Boolean get() = false
        val authorizesTransfer: Boolean get() = false
        override fun toString(): String = "CanonicalIdentityDecision(verdict=$verdict)"
    }

    fun assess(
        intent: A0CanonicalAcquisitionIntent,
        resolution: A0CanonicalAcquisitionResolver.Resolution,
        observedAlbum: A0CanonicalAlbumPolicy.Claim,
        preflight: A0CandidatePreflight.Assessment,
        observedVersionTags: Set<A0CanonicalAcquisitionIntent.VersionTag> = emptySet(),
    ): Decision {
        val reasons = linkedSetOf<Reason>()
        val album = A0CanonicalAlbumPolicy.assess(
            A0CanonicalAlbumPolicy.Target(
                requestedAlbum = intent.requestedAlbum,
                exactReleaseMbid = intent.ids?.releaseMbid?.takeIf { intent.strictExactRelease },
                expectedArtist = intent.artistCredits.firstOrNull(),
            ),
            observedAlbum,
        )
        if (preflight.confidence == A0CandidatePreflight.Confidence.UNSAFE) {
            reasons += Reason.PREFLIGHT_CONFLICT
        }
        if (album.status == A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM) {
            reasons += Reason.ALBUM_CONFLICT
        }
        // A different explicitly advertised version is a conflicting *claim*,
        // even when the MusicBrainz Recording MBID stays the same.
        if (observedVersionTags.any { it !in intent.requestedVersion }) {
            reasons += Reason.VERSION_CONFLICT
        }
        if (reasons.isNotEmpty()) return Decision(Verdict.INCOMPATIBLE, reasons)

        if (preflight.confidence == A0CandidatePreflight.Confidence.REVIEW) {
            reasons += Reason.PREFLIGHT_INSUFFICIENT
        }
        if (intent.requestedAlbum != null &&
            album.status != A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED) {
            reasons += Reason.ALBUM_UNCONFIRMED
        }
        if (intent.requestedVersion.isNotEmpty() && observedVersionTags.isEmpty()) {
            reasons += Reason.VERSION_UNCONFIRMED
        }
        // The resolver status has meaning only with an actual canonical
        // target. A manual-only title and album must NEVER borrow another
        // request's confirmed relation to become STRONG.
        if (intent.ids == null) reasons += Reason.CANONICAL_INCOMPLETE
        when (resolution.status) {
            A0CanonicalAcquisitionResolver.Status.CONFIRMED_RELATION -> Unit
            A0CanonicalAcquisitionResolver.Status.UNAVAILABLE ->
                reasons += Reason.CANONICAL_UNAVAILABLE
            A0CanonicalAcquisitionResolver.Status.AMBIGUOUS ->
                reasons += Reason.CANONICAL_AMBIGUOUS
            A0CanonicalAcquisitionResolver.Status.CONTRADICTED ->
                reasons += Reason.CANONICAL_CONFLICT
            A0CanonicalAcquisitionResolver.Status.MISSING,
            A0CanonicalAcquisitionResolver.Status.PARTIAL ->
                reasons += Reason.CANONICAL_INCOMPLETE
        }
        // There is no pre-transfer acoustic proof tying this concrete peer
        // file to an exact Release master. Even a matched Release Track does
        // not satisfy a strict edition/master requirement.
        if (intent.strictExactRelease) {
            reasons += Reason.EXACT_FILE_EDITION_UNVERIFIED
        }
        return if (reasons.isEmpty()) {
            Decision(Verdict.COMPATIBLE_STRONG_UNVERIFIED, emptySet())
        } else {
            Decision(Verdict.INCONCLUSIVE_MANUAL, reasons)
        }
    }
}
