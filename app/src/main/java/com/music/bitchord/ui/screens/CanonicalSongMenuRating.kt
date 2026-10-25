package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import com.music.bitchord.playback.androidCanonicalQueueResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L9 shared song-actions entry. It is intentionally invisible unless the
 * Playback-owned resolver proves the exact Recording and playable asset.
 * YouTube account Likes / Dislikes remain an unrelated menu feature.
 *
 * No rating is changed by opening, closing, scrolling or switching this menu.
 */
@Composable
internal fun CanonicalSongMenuRating(song: Song) {
    val context = LocalContext.current.applicationContext
    val cachedId = LocalCanonicalRatingDisplay.current.recordingIdForSong(song)
    val resolvedId by produceState<String?>(cachedId, context, song, cachedId) {
        // A verified Catalog MediaAsset works offline for a first local rating.
        // Don't require a Navidrome request when its local snapshot knows the
        // canonical recording and the editor will recheck availability on save.
        if (cachedId != null) {
            value = cachedId
            return@produceState
        }
        value = try {
            withContext(Dispatchers.IO) {
                CanonicalSongFeedbackTarget(androidCanonicalQueueResolver(context)).recordingId(song)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unproven identity: no fallback to title, artist or provider ID.
            null
        }
    }
    resolvedId?.let { canonicalId ->
        // The shared SongActionsSheet already provides group separators.
        // An extra divider here created a visibly doubled band above Edit.
        CanonicalRecordingFeedbackEntry(canonicalId)
    }
}
