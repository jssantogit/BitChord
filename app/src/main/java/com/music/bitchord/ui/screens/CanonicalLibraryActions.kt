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

internal fun canonicalLibraryAvailableActions(
    type: CanonicalLibraryEntityType,
    hasCanonicalIdentity: Boolean,
): List<CanonicalLibraryAction> = buildList {
    // Recorded song ratings have a real existing editor. Album/artist ratings
    // must not impersonate song ratings while their own persistence is pending.
    if (type == CanonicalLibraryEntityType.TRACK && hasCanonicalIdentity) {
        add(CanonicalLibraryAction.EDIT_RATING)
    }
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

/** Native playlist editor uses this exact source-aware, verified group resolver. */
internal suspend fun canonicalLibraryResolveRecordingIds(
    context: Context,
    selection: List<CanonicalLibrarySelection>,
    selectedSource: String?,
): List<String> {
    val groups = selection.map { target ->
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
    return canonicalLibraryBatchRecordingIds(groups)
}

internal sealed interface CanonicalLibraryPlaylistSaveResult {
    data class Saved(val added: Int, val unavailable: Int) : CanonicalLibraryPlaylistSaveResult
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
        // Re-check the target source inside the transaction. Never mix new
        // tracks from an unselected provider into a source-bound playlist.
        val allowed = if (current.sourceProviderKey == null) eligible else {
            eligible.takeIf { current.sourceProviderKey in permitted }.orEmpty()
        }
        val plan = planCanonicalPlaylistAppend(current.recordingIds, requestedIds, allowed)
        if (plan.addedIds.isNotEmpty() || playlistId == null) {
            repo.save(current.copy(recordingIds = plan.updatedIds))
        }
        CanonicalLibraryPlaylistSaveResult.Saved(plan.addedIds.size, plan.unavailableCount)
    }
}
