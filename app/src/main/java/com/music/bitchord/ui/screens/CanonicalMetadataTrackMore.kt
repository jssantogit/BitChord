package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.theme.ArtworkPalette

/** One source-neutral metadata row menu for verified MusicBrainz recordings.
 * Unlike playback, rating and external lookup do not require a MediaAsset. */
@Composable
internal fun CanonicalMetadataTrackMore(
    recordingId: String?,
    title: String,
    artist: String,
    palette: ArtworkPalette,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onRate: (String) -> Unit,
    onAddToPlaylist: (String, String, String) -> Unit,
    tag: String,
) {
    if (recordingId == null || !isVerifiedCatalogRecordingId(recordingId)) return
    val context = LocalContext.current.applicationContext
    Box {
        RowMoreButton(
            onClick = onOpen,
            tint = palette.onBackgroundVariant,
            contentDescription = "Ações da faixa",
            modifier = Modifier.testTag(tag),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(text = { Text("Avaliar faixa") }, onClick = {
                onDismiss()
                onRate(recordingId)
            })
            DropdownMenuItem(text = { Text("Adicionar à playlist") }, onClick = {
                onDismiss()
                onAddToPlaylist(recordingId, title, artist)
            })
            DropdownMenuItem(text = { Text("Ver no MusicBrainz") }, onClick = {
                onDismiss()
                val url = "https://musicbrainz.org/recording/$recordingId"
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
    }
}
