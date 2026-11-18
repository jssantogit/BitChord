package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.androidCanonicalQueueResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Canonical Recordings, never legacy Song/videoId or playlist-local IDs. */
internal fun canonicalSongPlaylistSelection(recordingId: String?): CanonicalLibrarySelection? =
    recordingId?.takeIf { it.isNotBlank() && it == it.trim() && it.length <= 256 }
        ?.let { CanonicalLibrarySelection(CanonicalLibraryEntityType.TRACK, it) }

private sealed interface PlaylistSongProof {
    data object Resolving : PlaylistSongProof
    data object Unavailable : PlaylistSongProof
    data class Available(val selection: CanonicalLibrarySelection) : PlaylistSongProof
}

/**
 * Single source of truth for Add-to-Playlist from player and online search.
 * Playback's canonical resolver must prove the Recording/asset/provider tuple.
 * No guessed title/artist lookup, no YouTube Music visibility, no legacy
 * LocalPlaylistStore: the shared picker saves CanonicalStaticPlaylist members.
 */
@Composable
internal fun CanonicalSongPlaylistPicker(song: Song, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val proof by produceState<PlaylistSongProof>(
        initialValue = PlaylistSongProof.Resolving,
        key1 = app, key2 = song,
    ) {
        // A cached rating/display mapping is not authoritative playlist proof.
        // Always resolve the exact Recording/MediaAsset/provider tuple afresh.
        val id = try {
            withContext(Dispatchers.IO) {
                CanonicalSongFeedbackTarget(androidCanonicalQueueResolver(app)).recordingId(song)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        value = canonicalSongPlaylistSelection(id)?.let(PlaylistSongProof::Available)
            ?: PlaylistSongProof.Unavailable
    }

    when (val state = proof) {
        is PlaylistSongProof.Available -> CanonicalLibraryPlaylistPicker(
            selection = listOf(state.selection),
            // The picker proves available assets for every selected source.
            // Null permits local and authenticated providers without guessing.
            selectedSource = null,
            onDismiss = onDismiss,
        )
        else -> PlaylistWizardSheetChrome(
            title = "Adicionar à playlist",
            onClose = onDismiss,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state == PlaylistSongProof.Resolving) {
                    NativeLoadingSpinner()
                } else {
                    Text(
                        "Esta faixa ainda não possui uma gravação identificada e disponível. " +
                            "Não foi criada uma playlist incompatível.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
