package com.music.bitchord.data.providers

import com.music.bitchord.data.catalog.metadata.CoverArtArchiveResponse
import com.music.bitchord.data.catalog.metadata.CoverArtArchiveTransport
import com.music.bitchord.data.lyrics.LyricsSource
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** Canonical provider identifiers shared across Android adapters. */
internal object BuiltInProviderKeys {
    val MUSICBRAINZ = ProviderKey("musicbrainz")
    val ITUNES = ProviderKey("itunes")
    val APPLE_ARTIST_ART = ProviderKey("apple_artist_art")
    val COVER_ART_ARCHIVE = ProviderKey("coverartarchive")
    val DISCOGS = ProviderKey("discogs")
    val LASTFM = ProviderKey("lastfm")
    val LISTENBRAINZ = ProviderKey("listenbrainz")

    fun lyrics(source: LyricsSource): ProviderKey =
        ProviderKey(source.name.lowercase(Locale.ROOT))
}

/**
 * Factory for capability-scoped provider descriptors.
 *
 * Keeps adapter implementation state distinct from user authorization and
 * dynamic operational health: unimplemented or unconfigured services never
 * claim READY by default.
 */
internal object BuiltInProviderDescriptors {
    fun musicBrainz(
        readiness: ProviderReadiness = ProviderReadiness.READY,
    ): ProviderDescriptor = ProviderDescriptor(
        key = BuiltInProviderKeys.MUSICBRAINZ,
        capabilities = setOf(
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.IDENTITY,
        ),
        readiness = readiness,
    )

    fun itunes(
        readiness: ProviderReadiness = ProviderReadiness.NOT_IMPLEMENTED,
    ): ProviderDescriptor = ProviderDescriptor(
        key = BuiltInProviderKeys.ITUNES,
        capabilities = setOf(
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.RELEASE_ARTWORK,
        ),
        readiness = readiness,
    )

    fun appleArtistArt(
        readiness: ProviderReadiness = ProviderReadiness.NOT_IMPLEMENTED,
    ): ProviderDescriptor = ProviderDescriptor(
        key = BuiltInProviderKeys.APPLE_ARTIST_ART,
        capabilities = setOf(ProviderCapability.ARTIST_ARTWORK),
        readiness = readiness,
    )

    fun coverArtArchive(
        readiness: ProviderReadiness = ProviderReadiness.READY,
    ): ProviderDescriptor = ProviderDescriptor(
        key = BuiltInProviderKeys.COVER_ART_ARCHIVE,
        capabilities = setOf(ProviderCapability.RELEASE_ARTWORK),
        readiness = readiness,
    )

    fun discogs(
        tokenConfigured: Boolean,
        implemented: Boolean = false,
    ): ProviderDescriptor = ProviderDescriptor(
        key = BuiltInProviderKeys.DISCOGS,
        capabilities = setOf(ProviderCapability.EDITION_METADATA),
        readiness = when {
            !implemented -> ProviderReadiness.NOT_IMPLEMENTED
            !tokenConfigured -> ProviderReadiness.NEEDS_CONFIGURATION
            else -> ProviderReadiness.READY
        },
    )

    fun offeredLyrics(
        readiness: ProviderReadiness = ProviderReadiness.READY,
    ): List<ProviderDescriptor> = LyricsSource.offered.map { source ->
        ProviderDescriptor(
            key = BuiltInProviderKeys.lyrics(source),
            capabilities = setOf(
                ProviderCapability.LYRICS_LOOKUP,
                ProviderCapability.LYRICS_SYNCED,
                ProviderCapability.LYRICS_PLAIN,
            ),
            readiness = readiness,
        )
    }
}

/**
 * Policy-guarded wrapper for [CoverArtArchiveTransport] enforcing
 * [ProviderCapability.RELEASE_ARTWORK] on [BuiltInProviderKeys.COVER_ART_ARCHIVE]
 * before dispatch, actively cancelling on mid-flight revocation, and suppressing
 * revoked responses.
 */
internal class PolicyGuardedCoverArtArchiveTransport(
    private val delegate: CoverArtArchiveTransport,
    private val gateProvider: () -> ProviderPolicyGate? = { ProviderRuntime.gateForCacheReadOrNull() },
) : CoverArtArchiveTransport {
    internal fun currentGate(): ProviderPolicyGate? = gateProvider()

    override suspend fun get(path: String): CoverArtArchiveResponse {
        val gate = gateProvider()
            ?: throw ProviderRequestDeniedException(
                ProviderCapability.RELEASE_ARTWORK,
                BuiltInProviderKeys.COVER_ART_ARCHIVE,
            )
        val ticket = gate.open(
            ProviderCapability.RELEASE_ARTWORK,
            BuiltInProviderKeys.COVER_ART_ARCHIVE,
        ) ?: throw ProviderRequestDeniedException(
            ProviderCapability.RELEASE_ARTWORK,
            BuiltInProviderKeys.COVER_ART_ARCHIVE,
        )
        val callerJob = coroutineContext[Job]
        val requestJob = Job(callerJob)
        val revoked = AtomicBoolean(false)
        val unregister = gate.onRevoke(ticket) {
            revoked.set(true)
            requestJob.cancel(CancellationException("Cover Art Archive capability revoked"))
        }
        return try {
            val response = withContext(requestJob) { delegate.get(path) }
            if (revoked.get() || !gate.accept(ticket)) {
                throw ProviderRequestDeniedException(
                    ProviderCapability.RELEASE_ARTWORK,
                    BuiltInProviderKeys.COVER_ART_ARCHIVE,
                )
            }
            response
        } catch (cancelled: CancellationException) {
            if (revoked.get() || !gate.accept(ticket)) {
                throw ProviderRequestDeniedException(
                    ProviderCapability.RELEASE_ARTWORK,
                    BuiltInProviderKeys.COVER_ART_ARCHIVE,
                )
            }
            throw cancelled
        } finally {
            unregister()
            requestJob.cancel()
        }
    }
}

/**
 * Policy-guarded boundary for rate-limited HTTP transports (such as iTunes'
 * 3.1s global spacing and Discogs' 3.0s spacing + 429 cooldown).
 */
internal class RateLimitedProviderTransport(
    // The test-only plain-JVM gate is never reachable after Android startup.
    // Production remains fail-closed if install/migration was not completed.
    private val gateProvider: () -> ProviderPolicyGate? = { ProviderRuntime.gateForCacheReadOrNull() },
) {
    /** Admission-only check for optional prerequisite probes (identity,
     * credentials) that must not run when the source is disabled. The actual
     * network dispatch still takes a revocable ticket in execute(). */
    fun permitted(capability: ProviderCapability, provider: ProviderKey): Boolean =
        gateProvider()?.open(capability, provider) != null

    suspend fun <T> execute(
        capability: ProviderCapability,
        provider: ProviderKey,
        scheduleLimited: suspend (block: suspend () -> T) -> T,
        networkCall: suspend () -> T,
    ): T {
        val gate = gateProvider()
            ?: throw ProviderRequestDeniedException(capability, provider)
        val ticket = gate.open(capability, provider)
            ?: throw ProviderRequestDeniedException(capability, provider)

        fun ensureAdmitted() {
            if (!gate.accept(ticket)) {
                throw ProviderRequestDeniedException(capability, provider)
            }
        }

        val callerJob = coroutineContext[Job]
        val transportJob = Job(callerJob)
        val revoked = AtomicBoolean(false)
        val unregister = gate.onRevoke(ticket) {
            revoked.set(true)
            transportJob.cancel(CancellationException("Provider capability revoked"))
        }
        return try {
            val result = withContext(transportJob) {
                ensureAdmitted()
                val response = scheduleLimited {
                    ensureAdmitted()
                    networkCall().also { ensureAdmitted() }
                }
                ensureAdmitted()
                response
            }
            if (revoked.get() || !gate.accept(ticket)) {
                throw ProviderRequestDeniedException(capability, provider)
            }
            result
        } catch (cancelled: CancellationException) {
            if (revoked.get() || !gate.accept(ticket)) {
                throw ProviderRequestDeniedException(capability, provider)
            }
            throw cancelled
        } finally {
            unregister()
            transportJob.cancel()
        }
    }
}

/**
 * Guards secondary image/CDN HTTP probes (such as HttpURLConnection range
 * requests for 600/720/1024px artwork variants or artist portraits) that
 * bypass the primary catalog Ktor client.
 */
internal class ArtworkProbePolicyGuard(
    private val gateProvider: () -> ProviderPolicyGate? = { ProviderRuntime.gateOrNull() },
) {
    suspend fun <T> probe(
        capability: ProviderCapability,
        provider: ProviderKey,
        deniedFallback: T? = null,
        onProbeError: () -> T? = { null },
        executeProbe: suspend () -> T?,
    ): T? {
        require(
            capability == ProviderCapability.RELEASE_ARTWORK ||
                capability == ProviderCapability.ARTIST_ARTWORK,
        ) { "ArtworkProbePolicyGuard only governs artwork capabilities" }
        val gate = gateProvider() ?: return deniedFallback
        val ticket = gate.open(capability, provider) ?: return deniedFallback
        val callerJob = coroutineContext[Job]
        val probeJob = Job(callerJob)
        val revoked = AtomicBoolean(false)
        val unregister = gate.onRevoke(ticket) {
            revoked.set(true)
            probeJob.cancel(CancellationException("Artwork capability revoked"))
        }
        return try {
            val result = withContext(probeJob) { executeProbe() }
            if (revoked.get() || !gate.accept(ticket)) {
                deniedFallback
            } else {
                result ?: onProbeError()
            }
        } catch (cancelled: CancellationException) {
            if (revoked.get() || !gate.accept(ticket)) deniedFallback
            else throw cancelled
        } catch (_: Exception) {
            if (!revoked.get() && gate.accept(ticket)) onProbeError() else deniedFallback
        } finally {
            unregister()
            probeJob.cancel()
        }
    }
}

/**
 * Dispatches lyric lookups strictly across eligible providers in user-configured
 * priority order, with zero hidden fallback when all providers are disabled.
 */
internal class LyricsCapabilityDispatcher(
    private val gateProvider: () -> ProviderPolicyGate? = { ProviderRuntime.gateOrNull() },
) {
    suspend fun <T : Any> fetchFirst(
        capability: ProviderCapability = ProviderCapability.LYRICS_LOOKUP,
        lookup: suspend (ProviderKey) -> T?,
    ): T? {
        require(
            capability == ProviderCapability.LYRICS_LOOKUP ||
                capability == ProviderCapability.LYRICS_SYNCED ||
                capability == ProviderCapability.LYRICS_PLAIN,
        ) { "Unsupported lyrics capability: $capability" }
        val gate = gateProvider() ?: return null
        val orderedProviders = gate.ordered(capability)
        if (orderedProviders.isEmpty()) return null

        val callerJob = coroutineContext[Job]
        val executor = ProviderQueryExecutor(gate)
        for (descriptor in orderedProviders) {
            if (callerJob?.isActive == false) break
            // The same ticket must cover the active lyric request, not just
            // the admission/publication edges. Revocation cancels a blocked
            // HTTP lookup immediately; other authorized providers survive.
            when (val result = executor.execute(capability, descriptor.key) {
                lookup(descriptor.key)
            }) {
                is ProviderCallResult.Success -> {
                    if (result.value != null) return result.value
                }
                is ProviderCallResult.Failed,
                ProviderCallResult.Denied,
                ProviderCallResult.Stale -> Unit
            }
        }
        return null
    }
}
