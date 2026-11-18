package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Actions for a verified catalog RecordingId. May be hosted in the shared
 * frosted HeldContextMenu, including metadata-only tracks without MediaAsset.
 * No provider availability or playback is inferred from the MBID. */
@Composable
internal fun CanonicalMetadataRecordingHeldActions(
    recordingId: String,
    title: String,
    artist: String,
    onDismiss: () -> Unit,
    onRate: (String) -> Unit,
    onAddToPlaylist: (String, String, String) -> Unit,
) {
    if (!isVerifiedCatalogRecordingId(recordingId)) return
    val context = LocalContext.current.applicationContext
    com.music.bitchord.ui.components.NativeCompactMenuItem(
        label = "Avaliar faixa",
        icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.RatingPlain,
        onClick = {
                    onDismiss()
                    onRate(recordingId)
                },
            )
    com.music.bitchord.ui.components.NativeCompactMenuItem(
        label = "Adicionar à playlist",
        icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.Add,
        onClick = {
                    onDismiss()
                    onAddToPlaylist(recordingId, title, artist)
                },
            )
    com.music.bitchord.ui.components.NativeCompactMenuItem(
        label = "Ver no MusicBrainz",
        icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.Link,
        onClick = {
                    onDismiss()
                    val url = "https://musicbrainz.org/recording/$recordingId"
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )

}
