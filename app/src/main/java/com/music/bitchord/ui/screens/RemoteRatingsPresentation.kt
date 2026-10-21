package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.provider.RemoteNumericRatingKind
import com.music.bitchord.data.catalog.provider.RemoteStarredKind
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRemoteRatingReader
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingAvailability
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingConsumerSnapshot
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingCoverage
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * This is presentation evidence ONLY. It never becomes a
 * RecordingRatingStore write or a ranker/smart-playlist input.
 */
internal data class RemoteRatingUiEvidence(
    val mediaAssetId: String,
    val numericValue: Int?,
    val cleared: Boolean,
    val starred: Boolean,
)

internal data class RemoteRatingUiRow(
    val recordingId: String,
    val title: String?,
    val artistDisplay: String?,
    val evidence: List<RemoteRatingUiEvidence>,
)

internal sealed interface RemoteRatingsUiState {
    data object Disabled : RemoteRatingsUiState
    data object Loading : RemoteRatingsUiState
    data object Unavailable : RemoteRatingsUiState
    data object Error : RemoteRatingsUiState
    data class Ready(
        val rows: List<RemoteRatingUiRow>,
        val coverage: RemoteRatingCoverage = RemoteRatingCoverage(),
    ) : RemoteRatingsUiState
}

/** Only canonical Catalog-proved RecordingIds group evidence; text NEVER identifies a recording. */
internal suspend fun presentRemoteRatings(
    snapshot: RemoteRatingConsumerSnapshot,
    recording: suspend (String) -> RecordingEntity?,
): List<RemoteRatingUiRow> {
    if (snapshot.availability != RemoteRatingAvailability.CACHED_COMPLETE) return emptyList()
    return snapshot.items.groupBy { it.recordingId }.map { (id, items) ->
        val metadata = try {
            recording(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Rating evidence survives temporary metadata unavailability.
            null
        }
        RemoteRatingUiRow(
            recordingId = id,
            title = metadata?.title,
            artistDisplay = metadata?.artistDisplay,
            evidence = items.distinctBy { it.providerKey to it.mediaAssetId }.map { item ->
                RemoteRatingUiEvidence(
                    mediaAssetId = item.mediaAssetId,
                    numericValue = if (item.numeric.kind == RemoteNumericRatingKind.VALUE) {
                        item.numeric.value
                    } else null,
                    cleared = item.numeric.kind == RemoteNumericRatingKind.CLEARED_ZERO,
                    starred = item.starred == RemoteStarredKind.STARRED,
                )
            },
        )
    }
}

/**
 * One account-scoped cached read per account/sync preference emission.
 * No polling, HTTP request, credential access, or silent first-party migration.
 */
@Composable
internal fun rememberRemoteRatingsState(): RemoteRatingsUiState {
    val context = LocalContext.current.applicationContext
    val persistence = remember(context) { SharedPrefsOpenSubsonicAccountPersistence(context) }
    val database = remember(context) { CanonicalCatalogDatabase.get(context) }
    val reader = remember(context, persistence, database) {
        OpenSubsonicRemoteRatingReader(database) { persistence.read() }
    }
    val state by produceState<RemoteRatingsUiState>(
        initialValue = RemoteRatingsUiState.Loading,
        key1 = reader,
        key2 = persistence,
    ) {
        try {
            persistence.observe().collectLatest {
                value = RemoteRatingsUiState.Loading
                val next = try {
                    withContext(Dispatchers.IO) {
                        val snapshot = reader.read()
                        if (snapshot.availability != RemoteRatingAvailability.CACHED_COMPLETE) {
                            RemoteRatingsUiState.Unavailable
                        } else {
                            val dao = database.catalogDao()
                            RemoteRatingsUiState.Ready(
                                rows = presentRemoteRatings(snapshot) { id ->
                                    dao.recordingById(id)
                                },
                                coverage = snapshot.coverage,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Do not show old cached rows as belonging to a new account.
                    RemoteRatingsUiState.Error
                }
                value = next
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = RemoteRatingsUiState.Error
        }
    }
    return state
}
