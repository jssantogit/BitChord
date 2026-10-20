package com.music.bitchord.data.catalog.provider.opensubsonic

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

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

/**
 * Catalog-owned B12/B13 capability seam: an explicit refresh may contact the
 * provider's public extension manifest, while preview is strictly read-only and
 * performs no HTTP. It never returns account config, auth, source IDs or URLs.
 *
 * Concurrent refreshes are last-request-wins, and a config/revision change
 * during suspension always invalidates the previously obtained evidence.
 */
internal class OpenSubsonicStreamPolicyGateway(
    private val savedConfig: () -> OpenSubsonicAccountConfig?,
    private val revisionNow: () -> Long = OpenSubsonicAccountRevisionClock::current,
    private val discover: suspend (OpenSubsonicAccountConfig, Long) -> OpenSubsonicStreamCapabilitySnapshot =
        { config, generation -> OpenSubsonicProvider(config).streamCapabilityEvidence(generation) },
) {
    private val refreshSequence = AtomicLong(0L)
    @Volatile private var cached: OpenSubsonicStreamLifecycleLease? = null

    fun preview(generation: Long, providerKey: String): OpenSubsonicProviderStreamHandoff =
        cached?.preview(generation, providerKey) ?: unavailable()

    suspend fun refresh(generation: Long, providerKey: String): OpenSubsonicProviderStreamHandoff {
        val sequence = refreshSequence.incrementAndGet()
        val revision = revisionNow()
        val config = try {
            savedConfig()
        } catch (_: Exception) {
            null
        }
        if (config == null || generation < 0 ||
            providerKey != "opensubsonic:${config.accountId}"
        ) {
            publish(sequence, null)
            return unavailable()
        }

        val provider = OpenSubsonicProvider(config)
        val evidence = try {
            discover(config, generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Manifest discovery may fail without changing cached music or
            // treating missing evidence as explicit server incompatibility.
            null
        }
        val lease = OpenSubsonicStreamLifecycleLease(
            providerKey = providerKey,
            generation = generation,
            initialRevision = revision,
            revisionNow = revisionNow,
            savedConfig = savedConfig,
            configMatches = provider::matchesSavedConfig,
            capability = evidence,
        )
        if (!lease.isCurrent()) {
            publish(sequence, null)
            return unavailable()
        }
        if (!publish(sequence, lease)) {
            // An older network completion must never reinstate old evidence.
            return preview(generation, providerKey)
        }
        return lease.preview(generation, providerKey)
    }

    private fun publish(sequence: Long, lease: OpenSubsonicStreamLifecycleLease?): Boolean =
        synchronized(this) {
            if (refreshSequence.get() != sequence) false else {
                cached = lease
                true
            }
        }

    private fun unavailable(): OpenSubsonicProviderStreamHandoff =
        OpenSubsonicProviderStreamHandoff(
            contractVersion = 1,
            lifecycle = OpenSubsonicStreamLeaseStatus.STALE,
            transcoding = OpenSubsonicStreamConfidence.UNKNOWN,
        )

    override fun toString(): String = "OpenSubsonicStreamPolicyGateway(<redacted>)"
}
