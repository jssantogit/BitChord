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
    ) {
        val group = releaseGroupMbid?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf(groupId::matches) ?: return
        if (!verified || kind !in setOf(
                DiscogsEditorialKind.ALBUM,
                DiscogsEditorialKind.EP,
                DiscogsEditorialKind.SINGLE,
            ) || discogsReleaseId !in 1..999_999_999_999L ||
            expiresAtMillis <= nowMillis
        ) return
        mutable.update { snapshot ->
            val current = snapshot[group].orEmpty()
                .filter { it.expiresAtMillis > nowMillis && it.proof.releaseId != discogsReleaseId }
            (snapshot.filterValues { it.isNotEmpty() } + (
                group to (current + Record(
                    DiscogsGroupTypeProof(discogsReleaseId, kind, verified = true),
                    expiresAtMillis,
                )).takeLast(24)
            )).takeLastGroups(64)
        }
        display.value = activeProofs(nowMillis)
    }

    fun activeProofs(nowMillis: Long): Map<String, List<DiscogsGroupTypeProof>> =
        mutable.value.mapValues { (_, records) ->
            records.filter { it.expiresAtMillis > nowMillis }.map { it.proof }
        }.filterValues { it.isNotEmpty() }

    fun clear() {
        mutable.value = emptyMap()
        display.value = emptyMap()
    }

    fun prune(nowMillis: Long) {
        mutable.update { map ->
            map.mapValues { (_, records) ->
                records.filter { it.expiresAtMillis > nowMillis }
            }.filterValues { it.isNotEmpty() }
        }
        display.value = activeProofs(nowMillis)
    }

    private fun Map<String, List<Record>>.takeLastGroups(limit: Int) =
        if (size <= limit) this else entries.takeLast(limit).associate { it.key to it.value }
}

/** Process-scoped read model, cleared when observations expire or app restarts. */
internal object DiscogsGroupEditorialEvidence {
    val store = DiscogsVerifiedGroupProofStore()
}
