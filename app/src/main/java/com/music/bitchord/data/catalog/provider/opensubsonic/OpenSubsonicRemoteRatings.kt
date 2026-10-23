package com.music.bitchord.data.catalog.provider.opensubsonic

import androidx.room.withTransaction
import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.RemoteRatingCoverageEntity
import com.music.bitchord.data.catalog.RemoteRatingEvidenceEntity
import com.music.bitchord.data.catalog.provider.ProviderLibrarySnapshot
import com.music.bitchord.data.catalog.provider.RemoteNumericRating
import com.music.bitchord.data.catalog.provider.RemoteNumericRatingKind
import com.music.bitchord.data.catalog.provider.RemoteStarredKind

/**
 * C16/C17. Apply only after canonical sync, inside its complete Room transaction.
 * Never persist native source keys, display text, protocol payloads or credentials.
 */
internal object OpenSubsonicRemoteRatingPersistence {
    suspend fun apply(
        dao: CanonicalCatalogDao,
        providerKey: String,
        scopeId: String,
        generation: Long,
        snapshot: ProviderLibrarySnapshot,
    ) {
        if (!snapshot.complete) return // no prune or promotion from a partial enumeration

        val rows = ArrayList<RemoteRatingEvidenceEntity>()
        var matched = 0
        var unmatched = 0
        var absent = 0
        var cleared = 0
        var valued = 0
        var invalid = 0
        var starred = 0
        var invalidStarred = 0
        for (track in snapshot.tracks) {
            // A provider-native song ID is only a lookup key, NEVER RecordingId.
            val key = track.externalTrackId.trim()
            val asset = if (key.isNotEmpty()) dao.mediaAsset(providerKey, key) else null
            val claimedRecording = if (key.isNotEmpty()) {
                dao.entityIdForExternalIdentity(providerKey, "recording", key)
            } else null
            if (asset == null || asset.provider != providerKey ||
                claimedRecording != asset.recordingId ||
                dao.recordingById(asset.recordingId) == null
            ) {
                unmatched += 1
                continue
            }
            matched += 1
            val evidence = track.remoteRating
            when (evidence.numeric.kind) {
                RemoteNumericRatingKind.NOT_PROVIDED -> absent++
                RemoteNumericRatingKind.CLEARED_ZERO -> cleared++
                RemoteNumericRatingKind.VALUE -> valued++
                RemoteNumericRatingKind.INVALID -> invalid++
            }
            when (evidence.starred) {
                RemoteStarredKind.STARRED -> starred++
                RemoteStarredKind.INVALID -> invalidStarred++
                else -> Unit
            }

            // Explicit zero is an observed clear, not an absent rating; INVALID
            // is counted but not displayed as a rated item.
            if (evidence.numeric.kind in setOf(RemoteNumericRatingKind.CLEARED_ZERO, RemoteNumericRatingKind.VALUE) ||
                evidence.starred == RemoteStarredKind.STARRED
            ) {
                rows += RemoteRatingEvidenceEntity(
                    providerKey = providerKey,
                    scopeId = scopeId,
                    mediaAssetId = asset.id,
                    recordingId = asset.recordingId,
                    numericKind = evidence.numeric.kind.name,
                    numericValue = evidence.numeric.value,
                    starredKind = evidence.starred.name,
                    generation = generation,
                )
            }
        }
        // Replace only REMOTE evidence for this one provider. First-party ratings
        // and other provider rows are never touched.
        dao.clearRemoteRatingEvidence(providerKey)
        if (rows.isNotEmpty()) dao.upsertRemoteRatingEvidence(rows)
        dao.upsertRemoteRatingCoverage(RemoteRatingCoverageEntity(
            providerKey = providerKey,
            scopeId = scopeId,
            generation = generation,
            enumerated = snapshot.tracks.size,
            matched = matched,
            unmatched = unmatched,
            absentNumeric = absent,
            clearedNumeric = cleared,
            valuedNumeric = valued,
            invalidNumeric = invalid,
            starred = starred,
            invalidStarred = invalidStarred,
        ))
    }
}

internal enum class RemoteRatingAvailability { UNAVAILABLE, CACHED_COMPLETE }

/** Counters only: suitable for D12, no music identity, titles, auth or URLs. */
internal data class RemoteRatingCoverage(
    val enumerated: Int = 0,
    val matched: Int = 0,
    val unmatched: Int = 0,
    val absentNumeric: Int = 0,
    val clearedNumeric: Int = 0,
    val valuedNumeric: Int = 0,
    val invalidNumeric: Int = 0,
    val starred: Int = 0,
    val invalidStarred: Int = 0,
)

internal data class RemoteRatingConsumerItem(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
    /** Opaque account-instance scope, not a credential or native song ID. */
    val scopeId: String,
    val generation: Long,
    val numeric: RemoteNumericRating,
    val starred: RemoteStarredKind,
) {
    override fun toString(): String = "RemoteRatingConsumerItem(<redacted>)"
}

internal data class RemoteRatingConsumerSnapshot(
    val availability: RemoteRatingAvailability,
    val coverage: RemoteRatingCoverage,
    val items: List<RemoteRatingConsumerItem>,
) {
    override fun toString(): String = "RemoteRatingConsumerSnapshot(availability=$availability, coverage=$coverage, itemCount=${items.size})"
}

/**
 * C18 Listening handoff: only reads the cached, complete, scoped remote snapshot.
 * Never touches the first-party RecordingRatingStore; no network or mutations.
 * A logout, account edit or account instance replacement makes old rows invisible.
 */
internal class OpenSubsonicRemoteRatingReader(
    private val database: CanonicalCatalogDatabase,
    private val currentAccount: () -> OpenSubsonicAccountRecord?,
) {
    suspend fun read(): RemoteRatingConsumerSnapshot {
        val account = currentAccount() ?: return unavailable()
        val provider = "opensubsonic:${account.accountId}"
        val scope = account.ratingScopeId
        val cached = database.withTransaction {
            val dao = database.catalogDao()
            val coverage = dao.remoteRatingCoverage(provider, scope) ?: return@withTransaction null
            coverage to dao.reconciledRemoteRatings(provider, scope)
        } ?: return unavailable()
        val after = currentAccount()
        if (after == null || after.accountId != account.accountId ||
            after.ratingScopeId != scope || after.serverUrl != account.serverUrl ||
            after.authMode != account.authMode || after.username != account.username
        ) return unavailable()

        val (coverage, rows) = cached
        return RemoteRatingConsumerSnapshot(
            availability = RemoteRatingAvailability.CACHED_COMPLETE,
            coverage = RemoteRatingCoverage(
                enumerated = coverage.enumerated,
                matched = coverage.matched,
                unmatched = coverage.unmatched,
                absentNumeric = coverage.absentNumeric,
                clearedNumeric = coverage.clearedNumeric,
                valuedNumeric = coverage.valuedNumeric,
                invalidNumeric = coverage.invalidNumeric,
                starred = coverage.starred,
                invalidStarred = coverage.invalidStarred,
            ),
            items = rows.map { row ->
                val kind = runCatching { RemoteNumericRatingKind.valueOf(row.numericKind) }
                    .getOrDefault(RemoteNumericRatingKind.INVALID)
                val numeric = if (kind == RemoteNumericRatingKind.VALUE &&
                    row.numericValue != null && row.numericValue in 1..5
                ) {
                    RemoteNumericRating(kind, row.numericValue)
                } else {
                    RemoteNumericRating(if (kind == RemoteNumericRatingKind.VALUE) {
                        RemoteNumericRatingKind.INVALID
                    } else { kind })
                }
                RemoteRatingConsumerItem(
                    recordingId = row.recordingId,
                    mediaAssetId = row.mediaAssetId,
                    providerKey = row.providerKey,
                    scopeId = row.scopeId,
                    generation = row.generation,
                    numeric = numeric,
                    starred = runCatching { RemoteStarredKind.valueOf(row.starredKind) }
                        .getOrDefault(RemoteStarredKind.INVALID),
                )
            },
        )
    }

    private fun unavailable(): RemoteRatingConsumerSnapshot = RemoteRatingConsumerSnapshot(
        availability = RemoteRatingAvailability.UNAVAILABLE,
        coverage = RemoteRatingCoverage(),
        items = emptyList(),
    )
}
