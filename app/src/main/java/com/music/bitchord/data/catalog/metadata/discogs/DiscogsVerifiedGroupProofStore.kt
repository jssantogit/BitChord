package com.music.bitchord.data.catalog.metadata.discogs

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Volatile editorial evidence, scoped to an exact MusicBrainz ReleaseGroup
 * and distinct, previously VERIFIED Discogs releases. Never persisted to Room.
 */
internal class DiscogsVerifiedGroupProofStore {
    private data class Record(
        val proof: DiscogsGroupTypeProof,
        val expiresAtMillis: Long,
        // Internal quarantine: contradictory repeat observations of the same
        // Release cannot contribute editorial votes until evidence expires.
        val contested: Boolean = false,
    )
    private val mutable = MutableStateFlow<Map<String, List<Record>>>(emptyMap())
    val proofs: StateFlow<Map<String, List<DiscogsGroupTypeProof>>> get() = display

    private val display = MutableStateFlow<Map<String, List<DiscogsGroupTypeProof>>>(emptyMap())
    private val groupId = Regex("^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")

    fun record(
        releaseGroupMbid: String?,
        discogsReleaseId: Long,
        kind: DiscogsEditorialKind?,
        verified: Boolean,
        expiresAtMillis: Long,
        nowMillis: Long,
        masterId: Long? = null,
    ) {
        val group = releaseGroupMbid?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf(groupId::matches) ?: return
        // Source-specific format descriptions map to our three existing
        // native shelf categories. Mixtape does NOT imply an album.
        val nativeKind = when (kind) {
            DiscogsEditorialKind.ALBUM -> DiscogsEditorialKind.ALBUM
            DiscogsEditorialKind.MINI_ALBUM, DiscogsEditorialKind.EP ->
                DiscogsEditorialKind.EP
            DiscogsEditorialKind.MAXI_SINGLE, DiscogsEditorialKind.SINGLE ->
                DiscogsEditorialKind.SINGLE
            else -> null
        }
        if (!verified || nativeKind == null ||
            discogsReleaseId !in 1..999_999_999_999L ||
            expiresAtMillis <= nowMillis
        ) return
        val incomingMaster = masterId?.takeIf { it in 1..999_999_999_999L }
        mutable.update { snapshot ->
            val live = snapshot[group].orEmpty().filter { it.expiresAtMillis > nowMillis }
            val previous = live.filter { it.proof.releaseId == discogsReleaseId }
            val disputed = previous.any { existing ->
                existing.contested || existing.proof.kind != nativeKind ||
                    (existing.proof.masterId != null && incomingMaster != null &&
                        existing.proof.masterId != incomingMaster)
            }
            // A missing Master is unknown, not a retraction of a previously
            // verified Master; preserve the more informative grouping.
            val resolvedMaster = incomingMaster
                ?: previous.firstNotNullOfOrNull { it.proof.masterId }
            val fresh = Record(
                DiscogsGroupTypeProof(discogsReleaseId, nativeKind, verified = true,
                    masterId = resolvedMaster),
                if (disputed) maxOf(expiresAtMillis,
                    previous.maxOfOrNull { it.expiresAtMillis } ?: expiresAtMillis)
                else expiresAtMillis,
                contested = disputed,
            )
            val current = live.filter { it.proof.releaseId != discogsReleaseId }
            (snapshot.filterValues { it.isNotEmpty() } + (
                group to (current + fresh).takeLast(24)
            )).takeLastGroups(64)
        }
        display.value = activeProofs(nowMillis)
    }

    fun activeProofs(nowMillis: Long): Map<String, List<DiscogsGroupTypeProof>> =
        mutable.value.mapValues { (_, records) ->
            records.filter { it.expiresAtMillis > nowMillis && !it.contested }
                .map { it.proof }
        }.filterValues { it.isNotEmpty() }

    fun clear() {
        mutable.value = emptyMap()
        display.value = emptyMap()
    }

    fun nextExpiryMillis(nowMillis: Long): Long? =
        mutable.value.values.asSequence().flatten()
            .map { it.expiresAtMillis }
            .filter { it > nowMillis }
            .minOrNull()

    fun prune(nowMillis: Long) {
        mutable.update { map ->
            map.mapValues { (_, records) ->
                records.filter { it.expiresAtMillis > nowMillis }
            }.filterValues { it.isNotEmpty() }
        }
        display.value = activeProofs(nowMillis)
    }

    private fun Map<String, List<Record>>.takeLastGroups(limit: Int) =
        if (size <= limit) this else entries.toList().takeLast(limit)
            .associate { (key, records) -> key to records }
}

/** Process-scoped read model, cleared when observations expire or app restarts. */
internal object DiscogsGroupEditorialEvidence {
    val store = DiscogsVerifiedGroupProofStore()
}
