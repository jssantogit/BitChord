package com.music.bitchord.acquisition.a0

import java.text.Normalizer
import java.util.Locale

/**
 * A read-only album-claim gate. A peer's metadata or folder is not a
 * MusicBrainz Release identity and cannot authenticate the actual audio.
 *
 * It is deliberately separate from the #178 catalogue adapter and from
 * the existing live/manual preflight-v4. No networking or transfers here.
 */
internal object A0CanonicalAlbumPolicy {
    data class Target(
        val requestedAlbum: String?,
        val exactReleaseMbid: String? = null,
    )
    data class Claim(
        /** Untrusted, peer-declared album label or immediate album folder. */
        val advertisedAlbum: String?,
    )
    enum class Status {
        UNCONSTRAINED,
        MATCHING_CLAIM_UNVERIFIED,
        INCONCLUSIVE_MANUAL,
        CONFLICTING_CLAIM,
    }
    enum class Reason {
        NO_ALBUM_CONSTRAINT,
        MATCHING_NAME_IS_UNVERIFIED,
        ALBUM_NOT_ADVERTISED,
        ALBUM_CLAIM_CONFLICT,
        EXACT_RELEASE_NOT_PROVEN,
    }
    data class Assessment(
        val status: Status,
        val reason: Reason,
    ) {
        /** Scoring decisions never authorize any P/F or SAF operation. */
        val authorizesTransfer: Boolean get() = false
    }

    fun assess(target: Target, claim: Claim): Assessment {
        val expected = normalize(target.requestedAlbum)
        if (expected.isEmpty()) {
            return Assessment(Status.UNCONSTRAINED, Reason.NO_ALBUM_CONSTRAINT)
        }
        val observed = normalize(claim.advertisedAlbum)
        if (observed.isEmpty()) {
            return Assessment(Status.INCONCLUSIVE_MANUAL, Reason.ALBUM_NOT_ADVERTISED)
        }
        if (observed != expected) {
            // Incompatible with the album *claim*. The actual file is not
            // proven wrong; the manual choice still remains available.
            return Assessment(Status.CONFLICTING_CLAIM, Reason.ALBUM_CLAIM_CONFLICT)
        }
        if (!target.exactReleaseMbid.isNullOrBlank()) {
            // Same release name is insufficient to prove a release edition or
            // master. An MBID must come from a trusted canonical contract,
            // not text supplied by a Soulseek peer.
            return Assessment(Status.INCONCLUSIVE_MANUAL, Reason.EXACT_RELEASE_NOT_PROVEN)
        }
        return Assessment(Status.MATCHING_CLAIM_UNVERIFIED,
            Reason.MATCHING_NAME_IS_UNVERIFIED)
    }

    private fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val folded = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
        return folded.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter(String::isNotEmpty).joinToString(" ")
    }
}
