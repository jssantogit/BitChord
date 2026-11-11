package com.music.bitchord.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.room.withTransaction
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.listening.CanonicalEntityRatingEntity
import com.music.bitchord.data.listening.ListeningEventDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes only proven CURRENT canonical identities and provider memberships.
 * Cross-entity ratings belong to their own first-party table, never to the
 * recording rating store. An artist display group uses its own independent
 * namespace and is accepted only with proven source-scoped membership.
 *
 * This also makes multi-selection one all-or-nothing operation: evidence
 * for every selected target must succeed before the Listening Room transaction.
 */
internal suspend fun rateCanonicalLibrarySelection(
    context: Context,
    targets: List<CanonicalLibrarySelection>,
    selectedSource: String?,
    value: Double,
): Boolean = withContext(Dispatchers.IO) {
    if ((value != 0.0 && !isValidCanonicalEntityRating(value)) ||
        targets.isEmpty() || targets.size > 500) return@withContext false
    val unique = targets.distinct()
    if (unique.size != targets.size ||
        unique.any { it.entityId.isBlank() || it.entityId != it.entityId.trim() ||
            it.entityId.length > 256 }) return@withContext false

    val app = context.applicationContext
    val providers = canonicalAvailablePlaylistSourceKeys(app).filter { key ->
        selectedSource == null || selectedSource == key
    }
    if (providers.isEmpty()) return@withContext false
    val catalog = CanonicalCatalogDatabase.get(app).catalogDao()
    val tracks = unique.filter { it.type == CanonicalLibraryEntityType.TRACK }
    val trackIds = tracks.map { it.entityId }
    val availableTracks = trackIds.chunked(300).flatMap { chunk ->
        catalog.browseAssetsForRecordingIds(providers, chunk)
    }.mapTo(HashSet()) { it.recordingId }
    if (!trackIds.all { it in availableTracks }) return@withContext false

    for (target in unique) {
        when (target.type) {
            CanonicalLibraryEntityType.TRACK -> Unit // Proven by the actual MediaAsset above.
            CanonicalLibraryEntityType.ALBUM -> {
                if (canonicalEntityRatingTarget(target) == null ||
                    catalog.releaseById(target.entityId) == null ||
                    providers.none { key ->
                        catalog.providerScopedReleaseRecordingIds(key, target.entityId).isNotEmpty()
                    }) return@withContext false
            }
            CanonicalLibraryEntityType.ARTIST -> {
                val identity = canonicalEntityRatingTarget(target)
                    ?: return@withContext false
                if (identity.entityType == "DISPLAY_ARTIST") {
                    // Prove that this display group has actual playable
                    // recordings in the selected source. Keep its key separate
                    // from real ArtistIds, and never infer the ArtistId from text.
                    val groupName = playlistDisplayArtistNameFromGroupId(target.entityId)
                        ?: return@withContext false
                    if (providers.none { key ->
                        catalog.playlistDisplayArtistRecordingIds(key, groupName).isNotEmpty()
                    }) return@withContext false
                } else if (catalog.artistById(target.entityId) == null ||
                    providers.none { key ->
                        catalog.providerScopedArtistRecordingIds(key, target.entityId).isNotEmpty()
                    }) return@withContext false
            }
        }
    }

    val listening = ListeningEventDatabase.get(app)
    val now = System.currentTimeMillis()
    listening.withTransaction {
        for (target in unique) {
            when (target.type) {
                CanonicalLibraryEntityType.TRACK -> {
                    if (value == 0.0)
                        listening.recordingRatingDao().clearRating(target.entityId, now)
                    else
                        listening.recordingRatingDao().setRating(target.entityId, value, now)
                }
                CanonicalLibraryEntityType.ALBUM, CanonicalLibraryEntityType.ARTIST -> {
                    val entity = canonicalEntityRatingTarget(target)
                        ?: error("Missing already-verified entity identity")
                    if (value == 0.0)
                        listening.canonicalEntityRatingDao().clear(entity.entityType, entity.entityId)
                    else
                        listening.canonicalEntityRatingDao().upsert(
                            CanonicalEntityRatingEntity(
                                entityType = entity.entityType,
                                entityId = entity.entityId,
                                ratingValue = value,
                                ratedAtEpochMs = now,
                            )
                        )
                }
            }
        }
    }
    true
}

/**
 * One existing native half-star dialog for both individual album/artist ratings
 * and compatible batches. No new legacy Material3 editor or fictitious star
 * propagation from Artist to its recordings.
 */
@Composable
internal fun CanonicalLibraryEntityRatingDialog(
    targets: List<CanonicalLibrarySelection>,
    selectedSource: String?,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val unique = remember(targets) { targets.distinct() }
    val single = unique.singleOrNull()
    val ratingTarget = single?.let(::canonicalEntityRatingTarget)
    val dao = remember(app) { ListeningEventDatabase.get(app).canonicalEntityRatingDao() }
    val ratingFlow = remember(dao, ratingTarget) {
        ratingTarget?.let { dao.observe(it.entityType, it.entityId) }
    }
    val current by (ratingFlow ?: kotlinx.coroutines.flow.flowOf(null))
        .collectAsState(initial = null)
    var saving by remember(targets) { mutableStateOf(false) }
    var denied by remember(targets) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = when {
        unique.size > 1 -> "Avaliar ${unique.size} itens"
        single?.type == CanonicalLibraryEntityType.ARTIST -> "Avaliar artista"
        single?.type == CanonicalLibraryEntityType.ALBUM -> "Avaliar álbum"
        else -> "Avaliar seleção"
    }
    CanonicalRecordingFeedbackPanel(
        rating = current?.ratingValue,
        title = title,
        saving = saving,
        denied = denied,
        onDismiss = { if (!saving) onDismiss() },
        onSave = { value ->
            if (!saving) {
                saving = true
                denied = false
                scope.launch {
                    val ok = try {
                        rateCanonicalLibrarySelection(app, unique, selectedSource, value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { false }
                    saving = false
                    if (ok) onDismiss() else denied = true
                }
            }
        },
    )
}
