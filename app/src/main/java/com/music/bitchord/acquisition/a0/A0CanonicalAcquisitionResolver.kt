package com.music.bitchord.acquisition.a0

import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Typed read-only bridge to the canonical owner (#178). An injected reader
 * supplies catalogue facts; #177 never queries MusicBrainz independently,
 * writes Room, or considers a peer-supplied MBID authoritative.
 *
 * CONFIRMED_RELATION means only that a known Recording appears on a known
 * Release in the catalogue. It does not verify peer file bytes or master.
 * The #178 DAO adapter will be connected only after its schema stabilizes.
 */
internal class A0CanonicalAcquisitionResolver(
    private val reader: suspend (A0CanonicalAcquisitionIntent.CanonicalIds) -> Lookup,
    private val timeoutMillis: Long = 2_000L,
) {
    init { require(timeoutMillis in 1L..10_000L) }

    class CatalogFacts(
        val releaseMbid: String?,
        val releaseGroupMbid: String?,
        recordingMbidsOnRelease: Set<String>,
    ) {
        val recordingMbidsOnRelease: Set<String> =
            Collections.unmodifiableSet(recordingMbidsOnRelease.toSet())
        override fun toString(): String = "CatalogFacts(redacted)"
    }

    sealed interface Lookup {
        class Found(val facts: CatalogFacts) : Lookup
        data object Missing : Lookup
        data object Unavailable : Lookup
        data object Ambiguous : Lookup
    }

    enum class Status {
        /** A Recording-to-specific-Release catalogue relation was confirmed. */
        CONFIRMED_RELATION,
        /** Some catalogue evidence exists, but an edition link is not proven. */
        PARTIAL,
        CONTRADICTED,
        AMBIGUOUS,
        MISSING,
        UNAVAILABLE,
    }

    class Resolution(val status: Status) {
        val provesAudioMaster: Boolean get() = false
        val authorizesTransfer: Boolean get() = false
        override fun toString(): String = "CanonicalResolution(status=$status)"
    }

    suspend fun resolve(intent: A0CanonicalAcquisitionIntent): Resolution {
        val ids = intent.ids ?: return Resolution(Status.MISSING)
        val lookup = try {
            withTimeoutOrNull(timeoutMillis) { reader(ids) } ?: Lookup.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Lookup.Unavailable
        }

        when (lookup) {
            Lookup.Unavailable -> return Resolution(Status.UNAVAILABLE)
            Lookup.Missing -> return Resolution(Status.MISSING)
            Lookup.Ambiguous -> return Resolution(Status.AMBIGUOUS)
            is Lookup.Found -> {
                val facts = lookup.facts
                // A Group and a particular edition are separate facts.
                if (ids.releaseGroupMbid != null && facts.releaseGroupMbid != null &&
                    !ids.releaseGroupMbid.equals(facts.releaseGroupMbid, ignoreCase = true)) {
                    return Resolution(Status.CONTRADICTED)
                }
                if (ids.releaseMbid != null && facts.releaseMbid != null &&
                    !ids.releaseMbid.equals(facts.releaseMbid, ignoreCase = true)) {
                    return Resolution(Status.CONTRADICTED)
                }
                // A Recording MBID can be shared by many editions. Inability
                // to prove its link to the selected Release is NOT a mismatch.
                val sameRelease = ids.releaseMbid != null &&
                    ids.releaseMbid.equals(facts.releaseMbid, ignoreCase = true)
                val recordingLinked = ids.recordingMbid != null &&
                    facts.recordingMbidsOnRelease.any {
                        it.equals(ids.recordingMbid, ignoreCase = true)
                    }
                val sameGroup = ids.releaseGroupMbid == null || (
                    facts.releaseGroupMbid != null &&
                        ids.releaseGroupMbid.equals(facts.releaseGroupMbid, ignoreCase = true)
                    )
                return Resolution(
                    if (sameRelease && recordingLinked && sameGroup)
                        Status.CONFIRMED_RELATION else Status.PARTIAL
                )
            }
        }
    }
}
