package com.music.bitchord.data.catalog.provider.opensubsonic

import java.util.concurrent.atomic.AtomicLong

/**
 * Account configuration mutations revoke all provider access leases across
 * repository instances in this Android process. Health and sync bookkeeping
 * never rotate this revision or delete cached canonical music metadata.
 */
internal object OpenSubsonicAccountRevisionClock {
    private val revision = AtomicLong(0L)

    fun current(): Long = revision.get()

    fun revoke(): Long = revision.incrementAndGet()
}

internal enum class OpenSubsonicStreamLeaseStatus {
    CURRENT,
    STALE,
}

/**
 * Versioned, read-only consumer metadata for Playback B12/B13. Do not interpret
 * CURRENT as a playable-now probe or ADVERTISED as a negotiated transcode.
 * Neither identifiers, URLs, raw headers nor credentials appear in toString.
 */
internal class OpenSubsonicProviderStreamHandoff internal constructor(
    val contractVersion: Int,
    val lifecycle: OpenSubsonicStreamLeaseStatus,
    val transcoding: OpenSubsonicStreamConfidence,
) {
    override fun toString(): String =
        "OpenSubsonicProviderStreamHandoff(v=$contractVersion, " +
            "lifecycle=$lifecycle, transcoding=$transcoding)"
}

/**
 * A private lifetime-bound account lease. C9 capability evidence may be
 * included for a pure preview but is never permission to play or transcode.
 * The closure and credential-bearing account config stay strictly in Catalog.
 */
internal class OpenSubsonicStreamLifecycleLease(
    private val providerKey: String,
    private val generation: Long,
    private val initialRevision: Long,
    private val revisionNow: () -> Long,
    private val savedConfig: () -> OpenSubsonicAccountConfig?,
    private val configMatches: (OpenSubsonicAccountConfig?) -> Boolean,
    private val capability: OpenSubsonicStreamCapabilitySnapshot? = null,
) {
    /** Cheap per-read revocation check (no Keystore or persisted configuration I/O). */
    fun revisionIsCurrent(): Boolean = revisionNow() == initialRevision

    /** Check before and after potentially long, credentialed network operations. */
    fun isCurrent(): Boolean {
        if (!revisionIsCurrent()) return false
        val configMatchesNow = try {
            configMatches(savedConfig())
        } catch (_: Exception) {
            false
        }
        return configMatchesNow && revisionIsCurrent()
    }

    fun preview(
        requestedGeneration: Long,
        requestedProviderKey: String,
    ): OpenSubsonicProviderStreamHandoff {
        val current = requestedGeneration == generation &&
            requestedProviderKey == providerKey && isCurrent()
        val confidence = if (current && capability != null) {
            val evidenceStillCurrent = try {
                capability.matches(generation, savedConfig())
            } catch (_: Exception) {
                false
            }
            if (evidenceStillCurrent && revisionIsCurrent()) {
                capability.transcoding.confidence
            } else {
                OpenSubsonicStreamConfidence.UNKNOWN
            }
        } else {
            OpenSubsonicStreamConfidence.UNKNOWN
        }
        return OpenSubsonicProviderStreamHandoff(
            contractVersion = 1,
            lifecycle = if (current && revisionIsCurrent()) {
                OpenSubsonicStreamLeaseStatus.CURRENT
            } else {
                OpenSubsonicStreamLeaseStatus.STALE
            },
            transcoding = confidence,
        )
    }

    override fun toString(): String = "OpenSubsonicStreamLifecycleLease(<redacted>)"
}
