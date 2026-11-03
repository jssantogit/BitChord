package com.music.bitchord.ui.screens

import android.content.Context
import androidx.room.withTransaction
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Typed UI references. Display-only artist groups may be navigated and added
 * to a playlist, but are NEVER treated as canonical ArtistIds for rating.
 */
internal enum class CanonicalLibraryEntityType { TRACK, ALBUM, ARTIST }
internal data class CanonicalLibrarySelection(
    val type: CanonicalLibraryEntityType,
    val entityId: String,
)

internal enum class CanonicalLibraryAction {
    EDIT_RATING, ADD_TO_PLAYLIST, ADD_NEXT, ADD_TO_QUEUE,
}

/** Rating keys distinguish a MusicBrainz ArtistId from a proven display group. */
internal data class CanonicalEntityRatingTarget(
    val entityType: String,
    val entityId: String,
)

internal fun canonicalEntityRatingTarget(
    selected: CanonicalLibrarySelection,
): CanonicalEntityRatingTarget? {
    val id = selected.entityId
    if (id.isBlank() || id != id.trim() || id.length > 256) return null
    return when (selected.type) {
        CanonicalLibraryEntityType.TRACK -> null
        CanonicalLibraryEntityType.ALBUM -> CanonicalEntityRatingTarget("ALBUM", id)
        CanonicalLibraryEntityType.ARTIST -> {
            // Some local libraries have real recordings for an artist before
            // MusicBrainz has verified an ArtistId. Keep these ratings in their
            // own namespace, NEVER fabricate an ArtistId from the name.
            val displayName = playlistDisplayArtistNameFromGroupId(id)
            CanonicalEntityRatingTarget(
                if (displayName != null) "DISPLAY_ARTIST" else "ARTIST", id,
            )
        }
    }
}

internal fun isValidCanonicalEntityRating(rating: Double): Boolean =
    rating.isFinite() && rating in 1.0..5.0 && rating * 2 == (rating * 2).toInt().toDouble()

internal fun canonicalLibraryAvailableActions(
    type: CanonicalLibraryEntityType,
    hasCanonicalIdentity: Boolean,
): List<CanonicalLibraryAction> = buildList {
    // Ratings live in separate first-party tables. The display-artist
    // namespace is distinct from verified ArtistId ratings.
    if (hasCanonicalIdentity) add(CanonicalLibraryAction.EDIT_RATING)
    add(CanonicalLibraryAction.ADD_TO_PLAYLIST)
    add(CanonicalLibraryAction.ADD_NEXT)
    add(CanonicalLibraryAction.ADD_TO_QUEUE)
}

internal fun toggleCanonicalLibrarySelection(
    chosen: List<CanonicalLibrarySelection>, target: CanonicalLibrarySelection,
): List<CanonicalLibrarySelection> = if (target in chosen) {
    chosen.filterNot { it == target }
} else {
    chosen + target
}


/**
 * The root Library list remains the source of truth for the drag order.
 * Keys match LazyColumn.items and LocalFolder.gridRows exactly; neither
 * header/toolbar rows nor a blank second card can become a selection.
 */
internal fun canonicalLibraryDragRows(
    ordered: List<CanonicalLibrarySelection>,
    grid: Boolean,
): Map<Any, List<CanonicalLibrarySelection>> =
    if (grid) ordered.chunked(2).mapIndexed { index, row ->
        "grid-$index-${row.first().entityId}" to row
    }.toMap()
    else ordered.associate { it.entityId as Any to listOf(it) }

internal fun canonicalLibraryDragHit(
    rows: Map<Any, List<CanonicalLibrarySelection>>,
    visibleKey: Any,
    pointerX: Float,
    width: Float,
    grid: Boolean,
): CanonicalLibrarySelection? {
    if (width <= 0f || pointerX < 0f || pointerX >= width) return null
    val entries = rows[visibleKey] ?: return null
    return if (grid) entries.getOrNull(if (pointerX < width / 2f) 0 else 1)
    else entries.singleOrNull()
}

/** Fill skipped rows when a finger moves faster than the list can draw frames. */
internal fun canonicalLibraryDragSpan(
    ordered: List<CanonicalLibrarySelection>,
    from: Int,
    to: Int,
): List<CanonicalLibrarySelection> {
    if (from !in ordered.indices || to !in ordered.indices) return emptyList()
    return ordered.subList(minOf(from, to), maxOf(from, to) + 1)
}

/** Exact RecordingId deduplication, never title/artist text or provider URL. */
internal fun canonicalLibraryBatchRecordingIds(
    groups: List<List<String>>,
): List<String> = groups.flatten().filter(String::isNotBlank).distinct()

/**
 * Pure preflight for a static playlist. Never remove unavailable previously
 * saved members, reorder them, or duplicate an already saved RecordingId.
 */
internal data class CanonicalPlaylistAppendPlan(
    val updatedIds: List<String>,
    val addedIds: List<String>,
    val unavailableCount: Int,
)

internal fun planCanonicalPlaylistAppend(
    current: List<String>,
    requested: List<String>,
    eligibleRecordingIds: Set<String>,
): CanonicalPlaylistAppendPlan {
    val distinct = requested.filter(String::isNotBlank).distinct()
    val available = distinct.filter { it in eligibleRecordingIds }
    val present = current.toHashSet()
    val newlyAdded = available.filterNot { it in present }
    return CanonicalPlaylistAppendPlan(
        updatedIds = current + newlyAdded,
        addedIds = newlyAdded,
        unavailableCount = distinct.count { it !in eligibleRecordingIds },
    )
}

/** A source change must not silently discard a selected album or artist. */
internal data class CanonicalLibraryResolvedSelection(
    val recordingIds: List<String>,
    val unavailableEntities: Int,
)

/** Recordings are the playback unit; album/artist cards never count as one song. */
internal fun canonicalLibrarySelectionSummary(
    selectedEntities: Int,
    result: CanonicalLibraryResolvedSelection?,
): String {
    if (result == null) return "Calculando faixas…"
    val tracks = result.recordingIds.size
    val unavailable = result.unavailableEntities
    val trackLabel = "$tracks " + if (tracks == 1) "faixa" else "faixas"
    val itemCount = selectedEntities - unavailable
    val itemsLabel = "$itemCount " + if (itemCount == 1) "item" else "itens"
    return if (unavailable == 0) "$trackLabel · $itemsLabel"
    else "$trackLabel · $unavailable " +
        if (unavailable == 1) "item indisponível" else "itens indisponíveis"
}

/** Only verified RecordingIds are included; unresolvable groups are reported. */
internal suspend fun canonicalLibraryResolveSelections(
    context: Context,
    selection: List<CanonicalLibrarySelection>,
    selectedSource: String?,
): CanonicalLibraryResolvedSelection {
    val groups = selection.distinct().map { target ->
        when (target.type) {
            CanonicalLibraryEntityType.TRACK -> listOf(target.entityId)
            CanonicalLibraryEntityType.ALBUM, CanonicalLibraryEntityType.ARTIST -> {
                canonicalNormalPlaylistGroupTracks(
                    context,
                    CanonicalPlaylistBrowseGroup(
                        id = target.entityId,
                        title = "",
                        type = if (target.type == CanonicalLibraryEntityType.ALBUM)
                            CanonicalPlaylistBrowseType.ALBUMS else CanonicalPlaylistBrowseType.ARTISTS,
                    ),
                    sourceProviderKey = selectedSource,
                ).map { it.id }
            }
        }
    }
    return CanonicalLibraryResolvedSelection(
        recordingIds = canonicalLibraryBatchRecordingIds(groups),
        unavailableEntities = groups.count { it.isEmpty() },
    )
}

/** Recheck current source assets before the embedded wizard persists members. */
internal fun canonicalNativeWizardSourceEligible(
    recordingIds: List<String>,
    availableRecordingIds: Set<String>,
): Boolean = recordingIds.distinct().all { it in availableRecordingIds }

/** Opening the existing wizard must not silently drop selected entities. */
internal fun canonicalLibraryWizardSeed(
    resolved: CanonicalLibraryResolvedSelection,
): List<String>? = resolved.recordingIds.distinct().takeIf {
    resolved.unavailableEntities == 0 && it.isNotEmpty()
}

internal suspend fun canonicalLibraryResolveRecordingIds(
    context: Context,
    selection: List<CanonicalLibrarySelection>,
    selectedSource: String?,
): List<String> = canonicalLibraryResolveSelections(
    context, selection, selectedSource,
).recordingIds

/** Fails closed if the destination playlist's source changed during IO.
 * A newly created playlist has no previous row and is checked separately.
 */
internal fun canonicalPlaylistTargetSourceUnchanged(
    initialSourceKey: String?,
    committedSourceKey: String?,
): Boolean = initialSourceKey == committedSourceKey

internal sealed interface CanonicalLibraryPlaylistSaveResult {
    data class Saved(val added: Int, val unavailable: Int) : CanonicalLibraryPlaylistSaveResult
    data class Unavailable(val count: Int) : CanonicalLibraryPlaylistSaveResult
    data object Missing : CanonicalLibraryPlaylistSaveResult
}

/**
 * Validation is performed against the CURRENT configured providers and Room
 * MediaAssets at commit time. Only ordinary canonical playlists are writable;
 * smart definitions are never included in this picker or modified here.
 *
 * The playlist's transaction preserves other edits and existing unavailable
 * members. Catalog and playlist DBs are distinct, so assets are revalidated
 * immediately before this one cross-table write, rather than trusting stale UI.
 */
internal suspend fun addCanonicalLibraryRecordingsToPlaylist(
    context: Context,
    playlistId: String?,
    newPlaylistName: String?,
    requested: List<String>,
): CanonicalLibraryPlaylistSaveResult = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val db = SmartPlaylistDefinitionDatabase.get(app)
    val repo = CanonicalStaticPlaylistRepository(db.staticPlaylistDao())
    val providerKeys = canonicalAvailablePlaylistSourceKeys(app)
    val existing = if (playlistId != null) repo.list().firstOrNull { it.id == playlistId }
        ?: return@withContext CanonicalLibraryPlaylistSaveResult.Missing else null
    val permitted = existing?.sourceProviderKey?.let { source ->
        providerKeys.filter { it == source }
    } ?: providerKeys
    val requestedIds = requested.distinct()
    val eligible = if (permitted.isEmpty() || requestedIds.isEmpty()) emptySet() else {
        val dao = CanonicalCatalogDatabase.get(app).catalogDao()
        requestedIds.chunked(300).flatMap { ids ->
            dao.browseAssetsForRecordingIds(permitted, ids)
        }.mapTo(HashSet()) { it.recordingId }
    }
    db.withTransaction {
        val current = if (playlistId == null) {
            SavedCanonicalStaticPlaylist(
                id = UUID.randomUUID().toString(),
                name = newPlaylistName?.trim().orEmpty(),
                recordingIds = emptyList(),
                sourceProviderKey = defaultCanonicalNormalPlaylistSource(providerKeys),
            )
        } else {
            // Re-read inside the playlist DB transaction: an edit may have
            // changed membership while the source verification was running.
            repo.list().firstOrNull { it.id == playlistId }
                ?: return@withTransaction CanonicalLibraryPlaylistSaveResult.Missing
        }
        // Two DBs cannot be locked atomically. A concurrent playlist editor
        // may change its target source between Catalog verification and this
        // transaction. Reject the entire append and let the user retry.
        if (playlistId != null && !canonicalPlaylistTargetSourceUnchanged(
                existing?.sourceProviderKey, current.sourceProviderKey,
            )) {
            return@withTransaction CanonicalLibraryPlaylistSaveResult.Unavailable(
                requestedIds.size,
            )
        }
        // Re-check the target source inside the transaction. Never mix new
        // tracks from an unselected provider into a source-bound playlist.
        val allowed = if (current.sourceProviderKey == null) eligible else {
            eligible.takeIf { current.sourceProviderKey in permitted }.orEmpty()
        }
        val plan = planCanonicalPlaylistAppend(current.recordingIds, requestedIds, allowed)
        // Never silently drop unavailable selected members in the name of
        // success. Explicit review must precede any partial insertion.
        if (plan.unavailableCount > 0) {
            return@withTransaction CanonicalLibraryPlaylistSaveResult.Unavailable(
                plan.unavailableCount,
            )
        }
        if (plan.addedIds.isNotEmpty() || playlistId == null) {
            repo.save(current.copy(recordingIds = plan.updatedIds))
        }
        CanonicalLibraryPlaylistSaveResult.Saved(plan.addedIds.size, plan.unavailableCount)
    }
}
