package com.music.bitchord.ui.screens

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
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
    val resolvedId by produceState<String?>(null, context, song) {
        value = try {
            withContext(Dispatchers.IO) {
                // Construct the Catalog-backed resolver off the main thread.
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
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        CanonicalRecordingFeedbackEntry(canonicalId)
    }
}
