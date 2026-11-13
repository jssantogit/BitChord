package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The album uses its existing native top bar; online-only context does not invent assets. */
@Composable
internal fun CanonicalCatalogReleaseOverflowMenu(
    browseId: String,
    title: String,
    subtitle: String,
    cover: String?,
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
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (target != null) {
            DropdownMenuItem(text = { Text("Avaliar álbum") }, onClick = {
                onDismiss()
                ratingOpen = true
            })
        }
        if (validCatalogMetadataPin(pin)) {
            DropdownMenuItem(
                text = { Text(if (isPinned) "Desafixar da Biblioteca" else "Fixar na Biblioteca") },
                onClick = { repo.toggle(pin); onDismiss() },
            )
        }
        if (target != null) {
            DropdownMenuItem(text = { Text("Ver no MusicBrainz") }, onClick = {
                onDismiss()
                val endpoint = if (target.entityType == "RELEASE_GROUP") "release-group" else "release"
                val url = Uri.parse("https://musicbrainz.org/$endpoint/" + target.entityId)
                context.startActivity(Intent(Intent.ACTION_VIEW, url))
            })
        }
    }
}
