package com.music.bitchord.acquisition.a0

import java.net.URI

/**
 * A0-only read-only admission gate for ONE bounded public-peer test candidate.
 *
 * A Soulseek listing cannot establish copyright permission or file identity.
 * The caller must provide permission evidence reviewed OUTSIDE Soulseek and
 * an independently known SHA-256 of the original source file. The gate does
 * no network I/O, disk writes, playback, peer contact or Catalog promotion.
 *
 * Passing this admission gate never authenticates an actual remote file.
 * A future permitted A0 transfer MUST compare the final streamed bytes
 * with expectedSha256 and fail closed on any mismatch.
 */
internal object A0OpenAudioCandidateGate {
    private const val MAX_PROBE_BYTES = 64L * 1024L * 1024L
    private const val MAX_REMOTE_PATH_UTF8_BYTES = 2048
    private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    private val ALLOWED_LICENSES = setOf(
        "CC0-1.0", "CC-BY-4.0", "CC-BY-SA-4.0", "PUBLIC-DOMAIN"
    )

    /** Explicit human-reviewed assertion, NOT automated license verification. */
    data class ReviewedReference(
        val sourcePage: String,
        val licenseIdentifier: String,
        val expectedFilename: String,
        val expectedSize: Long,
        val independentlyKnownSha256: String,
        val rightsReviewedOutsideSoulseek: Boolean,
    )

    /** Every value in this candidate originates from UNTRUSTED search metadata. */
    data class PublicPeerListing(
        val remoteFilename: String,
        val reportedSize: Long,
        val locked: Boolean,
        val freeSlot: Boolean,
    )

    enum class Rejection {
        RIGHTS_NOT_INDEPENDENTLY_REVIEWED,
        MISSING_INDEPENDENT_HASH,
        UNTRUSTWORTHY_SOURCE_REFERENCE,
        UNSUPPORTED_LICENSE,
        FILE_OUTSIDE_TEST_LIMIT,
        UNSAFE_REMOTE_FILENAME,
        NOT_PUBLICLY_ACCESSIBLE,
        NO_IMMEDIATE_SLOT,
        FILENAME_MISMATCH,
        SIZE_MISMATCH,
    }

    sealed class Decision {
        /** Sanitized: never includes remote path, username or IP. */
        data class EligibleForOneProbe(
            val expectedSize: Long,
            val expectedSha256: String,
            val licenseIdentifier: String,
        ) : Decision()

        data class Rejected(val reason: Rejection) : Decision()
    }

    fun evaluate(reference: ReviewedReference, listing: PublicPeerListing): Decision {
        fun reject(reason: Rejection): Decision = Decision.Rejected(reason)

        if (!reference.rightsReviewedOutsideSoulseek) {
            return reject(Rejection.RIGHTS_NOT_INDEPENDENTLY_REVIEWED)
        }
        if (!SHA256_HEX.matches(reference.independentlyKnownSha256)) {
            return reject(Rejection.MISSING_INDEPENDENT_HASH)
        }
        val source = runCatching { URI(reference.sourcePage) }.getOrNull()
        if (source == null || !source.scheme.equals("https", ignoreCase = true) ||
            source.host.isNullOrBlank() || source.userInfo != null ||
            source.port != -1 || source.fragment != null
        ) {
            return reject(Rejection.UNTRUSTWORTHY_SOURCE_REFERENCE)
        }
        if (reference.licenseIdentifier !in ALLOWED_LICENSES) {
            return reject(Rejection.UNSUPPORTED_LICENSE)
        }
        if (reference.expectedSize !in 1L..MAX_PROBE_BYTES ||
            listing.reportedSize !in 1L..MAX_PROBE_BYTES
        ) {
            return reject(Rejection.FILE_OUTSIDE_TEST_LIMIT)
        }
        val remoteName = listing.remoteFilename
        val normalizedPath = remoteName.replace('\\', '/')
        val segments = normalizedPath.split('/')
        if (remoteName.isEmpty() ||
            remoteName.toByteArray(Charsets.UTF_8).size > MAX_REMOTE_PATH_UTF8_BYTES ||
            remoteName.any { it == '\u0000' || it.code < 32 } ||
            normalizedPath.startsWith('/') ||
            segments.any { it.isEmpty() || it == "." || it == ".." } ||
            Regex("^[A-Za-z]:").containsMatchIn(remoteName)
        ) {
            return reject(Rejection.UNSAFE_REMOTE_FILENAME)
        }
        if (listing.locked) return reject(Rejection.NOT_PUBLICLY_ACCESSIBLE)
        if (!listing.freeSlot) return reject(Rejection.NO_IMMEDIATE_SLOT)

        val expectedName = reference.expectedFilename
        if (expectedName.isBlank() || expectedName.contains('/') ||
            expectedName.contains('\\') || expectedName.contains('\u0000') ||
            expectedName == "." || expectedName == ".." ||
            segments.last() != expectedName
        ) {
            return reject(Rejection.FILENAME_MISMATCH)
        }
        if (listing.reportedSize != reference.expectedSize) {
            return reject(Rejection.SIZE_MISMATCH)
        }
        // Never expose an untrusted peer filename in the eligible decision or
        // use that filename as a local file path. Strict SHA-256 AFTER receipt.
        return Decision.EligibleForOneProbe(
            reference.expectedSize,
            reference.independentlyKnownSha256.lowercase(),
            reference.licenseIdentifier,
        )
    }
}
