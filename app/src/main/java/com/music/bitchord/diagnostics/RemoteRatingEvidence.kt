package com.music.bitchord.diagnostics

/**
 * D12: data-only evidence for a remote rating boundary. Never use `starred`
 * as a synthetic numeric rating or write the remote value into local Room.
 */
enum class RemoteRatingFieldTruth {
    UNKNOWN, UNSUPPORTED, ABSENT, ZERO, RATED, INVALID;

    companion object {
        fun classify(present: Boolean?, value: Int?, supported: Boolean? = null): RemoteRatingFieldTruth =
            when {
                supported == false -> UNSUPPORTED
                present == null -> UNKNOWN
                !present -> ABSENT
                value == null || value !in 0..5 -> INVALID
                value == 0 -> ZERO
                else -> RATED
            }
    }
}

enum class CanonicalRatingMatchTruth {
    UNKNOWN, UNVERIFIED, UNMATCHED, AMBIGUOUS, MATCHED;

    companion object {
        fun fromEvidence(identityProven: Boolean?, matches: Int): CanonicalRatingMatchTruth =
            when {
                identityProven == null -> UNKNOWN
                !identityProven -> UNVERIFIED
                matches < 0 -> UNKNOWN
                matches == 0 -> UNMATCHED
                matches == 1 -> MATCHED
                else -> AMBIGUOUS
            }
    }
}

/** Aggregate only; no rating numbers, account keys, source IDs or song titles. */
data class RemoteRatingEvidenceSummary(
    val total: Int,
    val rated: Int,
    val cleared: Int,
    val absent: Int,
    val unsupported: Int,
    val unknown: Int,
    val invalid: Int,
    val starred: Int,
    val unknownStarred: Int,
    val truncated: Boolean,
)

/**
 * Per-enumeration bounded counters. This object retains no items, values or IDs.
 * Invoke only from an owner-approved provider/canonical boundary. Failure to
 * instrument must not alter the enumeration transaction.
 */
class RemoteRatingEvidenceTally(private val limit: Int = 10_000) {
    init { require(limit in 1..100_000) }
    private var total = 0
    private var rated = 0
    private var cleared = 0
    private var absent = 0
    private var unsupported = 0
    private var unknown = 0
    private var invalid = 0
    private var starred = 0
    private var unknownStarred = 0
    private var truncated = false

    @Synchronized
    fun add(field: RemoteRatingFieldTruth, starred: Boolean?) {
        if (total >= limit) {
            truncated = true
            return
        }
        total++
        when (field) {
            RemoteRatingFieldTruth.RATED -> rated++
            RemoteRatingFieldTruth.ZERO -> cleared++
            RemoteRatingFieldTruth.ABSENT -> absent++
            RemoteRatingFieldTruth.UNSUPPORTED -> unsupported++
            RemoteRatingFieldTruth.UNKNOWN -> unknown++
            RemoteRatingFieldTruth.INVALID -> invalid++
        }
        if (starred == true) this.starred++
        if (starred == null) unknownStarred++
    }

    @Synchronized
    fun snapshot(): RemoteRatingEvidenceSummary = RemoteRatingEvidenceSummary(
        total, rated, cleared, absent, unsupported, unknown, invalid,
        starred, unknownStarred, truncated,
    )
}
