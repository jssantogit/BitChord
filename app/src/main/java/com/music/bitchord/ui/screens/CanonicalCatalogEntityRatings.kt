package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.listening.CanonicalEntityRatingEntity
import com.music.bitchord.data.listening.ListeningEventDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val CATALOG_ENTITY_MBID = Regex(
    """^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"""
)

/** Online catalog identity comes from the opened typed route, never a display title.
 * ReleaseGroup and specific Release are different rating targets. */
internal fun canonicalCatalogEntityRatingTarget(browseId: String): CanonicalEntityRatingTarget? {
    val types = listOf(
        "catalog:ARTIST:" to "ARTIST",
        "catalog:RELEASE:" to "ALBUM",
        "catalog:RELEASE_GROUP:" to "RELEASE_GROUP",
    )
    val pair = types.firstOrNull { browseId.startsWith(it.first) } ?: return null
    val id = browseId.removePrefix(pair.first)
    return id.takeIf(CATALOG_ENTITY_MBID::matches)?.let {
        CanonicalEntityRatingTarget(pair.second, it.lowercase(Locale.ROOT))
    }
}

internal fun canonicalPublicPlaylistRatingTarget(mbid: String): CanonicalEntityRatingTarget? =
    mbid.takeIf(CATALOG_ENTITY_MBID::matches)?.let {
        CanonicalEntityRatingTarget("PLAYLIST", "public:" + it.lowercase(Locale.ROOT))
    }

/** Local playlists already own typed durable IDs; don't turn them into album IDs. */
internal fun canonicalOwnedPlaylistRatingTarget(id: String): CanonicalEntityRatingTarget? =
    id.takeIf { validLibraryPlaylistPin(LibraryPlaylistPin(it, "Playlist")) }?.let {
        CanonicalEntityRatingTarget("PLAYLIST", it)
    }

internal fun canPersistCanonicalCatalogRating(target: CanonicalEntityRatingTarget): Boolean =
    when (target.entityType) {
        "ARTIST", "ALBUM", "RELEASE_GROUP" -> CATALOG_ENTITY_MBID.matches(target.entityId)
        "PLAYLIST" -> validLibraryPlaylistPin(LibraryPlaylistPin(target.entityId, "Playlist"))
        else -> false
    }

@Composable
internal fun rememberCanonicalCatalogEntityRating(target: CanonicalEntityRatingTarget?): State<Double?> {
    val context = LocalContext.current.applicationContext
    val dao = remember(context) { ListeningEventDatabase.get(context).canonicalEntityRatingDao() }
    return produceState<Double?>(initialValue = null, key1 = target, key2 = dao) {
        if (target == null || !canPersistCanonicalCatalogRating(target)) return@produceState
        dao.observe(target.entityType, target.entityId).collect { saved ->
            value = saved?.ratingValue?.takeIf(::isValidCanonicalEntityRating)
        }
    }
}

/** Reuses the exact native half-star editor; only first-party Listening Room is writable. */
@Composable
internal fun CanonicalCatalogEntityRatingDialog(
    target: CanonicalEntityRatingTarget,
    title: String,
    onDismiss: () -> Unit,
    anchorBounds: androidx.compose.ui.unit.IntRect? = null,
) {
    val context = LocalContext.current.applicationContext
    val dao = remember(context) { ListeningEventDatabase.get(context).canonicalEntityRatingDao() }
    val current by rememberCanonicalCatalogEntityRating(target)
    val scope = rememberCoroutineScope()
    var saving by remember(target) { mutableStateOf(false) }
    var denied by remember(target) { mutableStateOf(false) }
    CanonicalRecordingFeedbackPanel(
        rating = current,
        title = title,
        anchorBounds = anchorBounds,
        saving = saving,
        denied = denied,
        onDismiss = { if (!saving) onDismiss() },
        onSave = { score ->
            if (!saving) {
                saving = true
                denied = false
                scope.launch {
                    val saved = try {
                        if (!canPersistCanonicalCatalogRating(target) ||
                            (score != 0.0 && !isValidCanonicalEntityRating(score))) false
                        else withContext(Dispatchers.IO) {
                            if (score == 0.0) {
                                dao.clear(target.entityType, target.entityId)
                            } else {
                                dao.upsert(CanonicalEntityRatingEntity(
                                    entityType = target.entityType,
                                    entityId = target.entityId,
                                    ratingValue = score,
                                    ratedAtEpochMs = System.currentTimeMillis(),
                                ))
                            }
                            true
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { false }
                    saving = false
                    if (saved) onDismiss() else denied = true
                }
            }
        },
    )
}
