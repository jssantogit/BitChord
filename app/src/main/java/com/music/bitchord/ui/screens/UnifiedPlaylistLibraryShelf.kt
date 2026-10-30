package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.data.listening.SmartPlaylistV2DefinitionRepository
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.AccentRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * First-party playlist surface in the Library itself. V2 definitions and
 * locally persisted normal playlists are two independent storage systems;
 * never manufacture a video/browse ID for a V2 recording collection.
 */
internal sealed interface LibraryPlaylistCard {
    val id: String
    val title: String
    data class Smart(
        val definition: SavedSmartPlaylistV2Definition,
    ) : LibraryPlaylistCard {
        override val id: String get() = "v2:" + definition.id
        override val title: String get() = definition.name
    }
    data class CanonicalNormal(val definition: SavedCanonicalStaticPlaylist) : LibraryPlaylistCard {
        override val id: String get() = "normal:" + definition.id
        override val title: String get() = definition.name
    }
    data class Normal(val shelfItem: ShelfItem) : LibraryPlaylistCard {
        override val id: String get() = "local:" + shelfItem.browseId.orEmpty()
        override val title: String get() = shelfItem.title
    }
}

internal fun unifiedPlaylistRows(cards: List<LibraryPlaylistCard>): List<List<LibraryPlaylistCard>> =
    cards.chunked(2)

/** Library category owns one complete vertical list; no truncation or grid toggle. */
internal fun playlistShelfRows(
    cards: List<LibraryPlaylistCard>,
    expanded: Boolean,
): List<List<LibraryPlaylistCard>> =
    cards.map { listOf(it) }

@Composable
internal fun UnifiedPlaylistLibraryShelf(
    normalPlaylists: List<ShelfItem>,
    onNewSmartPlaylist: () -> Unit,
    onOpenSmartPlaylist: (String) -> Unit,
    onOpenCanonicalNormalPlaylist: (String) -> Unit,
    onOpenNormalPlaylist: (ShelfItem) -> Unit,
    onSmartAction: (String, CanonicalCollectionIntent) -> Unit,
    onCanonicalNormalAction: (String, CanonicalCollectionIntent) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repo = remember(context) {
        SmartPlaylistV2DefinitionRepository(
            SmartPlaylistDefinitionDatabase.get(context).v2DefinitionDao(),
        )
    }
    val normalRepo = remember(context) {
        CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(context).staticPlaylistDao(),
        )
    }
    var failed by remember { mutableStateOf(false) }
    var normalFailed by remember { mutableStateOf(false) }
    val canonicalNormal by produceState(
        initialValue = emptyList<SavedCanonicalStaticPlaylist>(), key1 = normalRepo,
    ) {
        try {
            normalRepo.observe().collect {
                value = it
                normalFailed = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { normalFailed = true }
    }
    val smart by produceState(initialValue = emptyList<SavedSmartPlaylistV2Definition>(), key1 = repo) {
        try {
            repo.observe().collect {
                value = it
                failed = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { failed = true }
    }
    // Stable IDs make editing, queueing and ordering independent of display
    // names; only locally saved normal playlists are included in this slot.
    val cards = remember(smart, canonicalNormal, normalPlaylists) {
        smart.map { LibraryPlaylistCard.Smart(it) } +
            canonicalNormal.map { LibraryPlaylistCard.CanonicalNormal(it) } +
            normalPlaylists.filter { it.browseId?.startsWith("local:playlist:") == true }
                .map { LibraryPlaylistCard.Normal(it) }
    }
    var openedCard by remember { mutableStateOf<LibraryPlaylistCard?>(null) }
    val rows = playlistShelfRows(cards, expanded = false)

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding()
            .padding(top = 64.dp, bottom = 12.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Text("Playlists", style = MaterialTheme.typography.headlineLarge)
        }
        if (failed) {
            Text("Não foi possível carregar playlists inteligentes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        if (normalFailed) {
            Text("Não foi possível carregar playlists normais canônicas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        if (cards.isEmpty()) {
            Text("Suas playlists aparecerão aqui.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { card ->
                    val openCard: () -> Unit = {
                        when (card) {
                            is LibraryPlaylistCard.Smart ->
                                openedCard = card
                            is LibraryPlaylistCard.Normal ->
                                onOpenNormalPlaylist(card.shelfItem)
                            is LibraryPlaylistCard.CanonicalNormal ->
                                openedCard = card
                        }
                    }
                    val playCard: (() -> Unit)? = when (card) {
                        is LibraryPlaylistCard.Smart ->
                            { { onSmartAction(card.definition.id, CanonicalCollectionIntent.PLAY_NOW) } }
                        is LibraryPlaylistCard.CanonicalNormal ->
                            { { onCanonicalNormalAction(card.definition.id, CanonicalCollectionIntent.PLAY_NOW) } }
                        is LibraryPlaylistCard.Normal -> null
                    }
                    UnifiedPlaylistCompactRow(
                        card = card,
                        onClick = openCard,
                        onPlay = playCard,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        PlaylistWizardSecondaryAction(
            label = "+ Nova playlist",
            onClick = onNewSmartPlaylist,
        )
    }
    openedCard?.let { selected ->
        CanonicalPlaylistDetailScreen(
            card = selected,
            onClose = { openedCard = null },
            onEdit = {
                openedCard = null
                when (selected) {
                    is LibraryPlaylistCard.Smart ->
                        onOpenSmartPlaylist(selected.definition.id)
                    is LibraryPlaylistCard.CanonicalNormal ->
                        onOpenCanonicalNormalPlaylist(selected.definition.id)
                    is LibraryPlaylistCard.Normal ->
                        onOpenNormalPlaylist(selected.shelfItem)
                }
            },
            onAction = { intent ->
                when (selected) {
                    is LibraryPlaylistCard.Smart ->
                        onSmartAction(selected.definition.id, intent)
                    is LibraryPlaylistCard.CanonicalNormal ->
                        onCanonicalNormalAction(selected.definition.id, intent)
                    is LibraryPlaylistCard.Normal -> Unit
                }
            },
        )
    }
}

@Composable
private fun UnifiedPlaylistTile(
    card: LibraryPlaylistCard,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlay: (() -> Unit)?,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val image = (card as? LibraryPlaylistCard.Normal)
                    ?.shelfItem?.thumbnailUrl?.takeIf { it.isNotBlank() }
                if (image != null) {
                    AsyncImage(model = image, contentDescription = card.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp)))
                } else {
                    Icon(
                        if (card is LibraryPlaylistCard.Smart)
                            Icons.Rounded.AutoAwesome else Icons.Rounded.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
        }
        Text(card.title, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (card is LibraryPlaylistCard.Smart) "Inteligente" else "Normal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onPlay != null) {
            TextButton(onClick = onPlay, modifier = Modifier.align(Alignment.Start)) {
                Text("Reproduzir", color = AccentRed)
            }
        }
    }
}

/** Compact vertical Library presentation; the same actions work in the grid. */
@Composable
private fun UnifiedPlaylistCompactRow(
    card: LibraryPlaylistCard,
    onClick: () -> Unit,
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val canonicalCover by produceState<V2DisplayRow?>(
        initialValue = null, key1 = card.id,
    ) {
        value = when (card) {
            is LibraryPlaylistCard.CanonicalNormal -> {
                try {
                    val firstId = card.definition.recordingIds.firstOrNull()
                    if (firstId == null) null else canonicalNormalPlaylistSelectedRows(
                        context, listOf(firstId), card.definition.sourceProviderKey,
                    ).firstOrNull()?.takeIf { it.title != "Gravação indisponível" }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            else -> null
        }
    }
    Row(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .heightIn(min = 58.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val image = (card as? LibraryPlaylistCard.Normal)
                ?.shelfItem?.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (canonicalCover != null) {
                SmartPlaylistPreviewArtwork(canonicalCover!!, Modifier.size(56.dp))
            } else if (image != null) {
                AsyncImage(model = image, contentDescription = card.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
            } else {
                Card(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Icon(
                            if (card is LibraryPlaylistCard.Smart)
                                Icons.Rounded.AutoAwesome else Icons.Rounded.LibraryMusic,
                            contentDescription = null,
                            modifier = Modifier.size(25.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (card is LibraryPlaylistCard.Smart) "Inteligente" else "Normal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
