package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The album uses its existing native top bar; online-only context does not invent assets. */
@Composable
internal fun CanonicalCatalogReleaseOverflowMenu(
    browseId: String,
    title: String,
    subtitle: String,
    cover: String?,
    plateTint: Color? = null,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val target = remember(browseId) { canonicalCatalogEntityRatingTarget(browseId) }
    val repo = remember(context) { CatalogMetadataPinRepository.get(context.applicationContext) }
    val saved by repo.pins.collectAsStateWithLifecycle()
    val pin = remember(browseId, title, subtitle, cover) {
        CatalogMetadataPin(browseId, title.trim().take(240), subtitle.take(240),
            trustedCatalogMetadataPinArtwork(cover))
    }
    val isPinned = saved.any { it.browseId == browseId }
    var ratingOpen by remember(browseId) { mutableStateOf(false) }
    if (ratingOpen && target != null) {
        CanonicalCatalogEntityRatingDialog(
            target = target, title = "Avaliar álbum",
            onDismiss = { ratingOpen = false },
        )
    }
    com.music.bitchord.ui.components.NativeCompactOverflowMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        plateTint = plateTint,
    ) {
        if (target != null) {
            com.music.bitchord.ui.components.NativeCompactMenuItem(
                label = "Avaliar álbum",
                icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.RatingPlain,
                onClick = {
                    onDismiss()
                    ratingOpen = true
                },
            )
        }
        if (validCatalogMetadataPin(pin)) {
            com.music.bitchord.ui.components.NativeCompactMenuItem(
                label = if (isPinned) "Desafixar da Biblioteca" else "Fixar na Biblioteca",
                icon = com.music.bitchord.ui.icons.BitChordIcons.Pin,
                onClick = { repo.toggle(pin); onDismiss() },
            )
        }
        if (target != null) {
            com.music.bitchord.ui.components.NativeCompactMenuItem(
                label = "Ver no MusicBrainz",
                icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.Globe,
                onClick = {
                    onDismiss()
                    val endpoint = if (target.entityType == "RELEASE_GROUP") "release-group" else "release"
                    val url = Uri.parse("https://musicbrainz.org/$endpoint/" + target.entityId)
                    context.startActivity(Intent(Intent.ACTION_VIEW, url))
                },
            )
        }
    }
}
