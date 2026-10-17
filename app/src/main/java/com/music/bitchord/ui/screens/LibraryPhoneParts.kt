package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedCollection
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ReplayCardRowSkeleton
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.replay.ReplayCardRow
import com.music.bitchord.ui.replay.ReplayHeroCard
import com.music.bitchord.ui.replay.ReplayStoryPage

// The phone's halves of the shared Library page: its list of folders, what
// sits on its "On device" shelf, and the way in to Replay at its head.

/**
 * The phone's folders, drawn as a list under the Replay cards: Downloads and
 * Local Music, the song cache when Settings has it shown, and the two legacy
 * file-server libraries it can reach. OpenSubsonic's canonical browse entry is
 * hosted by [LibraryReplayEntry] instead because sending it through ShelfItem
 * would route it into the legacy browse/detail domain that this slice is
 * explicitly replacing rather than extending.
 */
@Composable
fun libraryLinks(): List<LibraryLink> {
    val webdavConfigured by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val smbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val spotifyConnected by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    fun link(title: String, subtitle: String, browseId: String, icon: ImageVector = deviceFolderIcon(browseId) ?: Icons.Rounded.Cloud) = LibraryLink(
        item = ShelfItem(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = null,
            videoId = null,
            browseId = browseId,
        ),
        icon = icon,
    )
    return listOfNotNull(
        link(
            stringResource(R.string.downloads),
            stringResource(R.string.downloaded_songs),
            "local:downloads",
        ),
        link(
            stringResource(R.string.local_music),
            stringResource(R.string.audio_files_on_device),
            "local:all",
        ),
        // Opt-in from Settings → Storage: what the song cache is holding from
        // YouTube and JioSaavn. See [com.music.bitchord.playback.AudioCache.cachedSongs].
        link(
            stringResource(R.string.cached_songs),
            stringResource(R.string.cached_songs_subtitle),
            CACHE_FOLDER_BROWSE_ID,
        ).takeIf { showCacheFolder },
        // Only once set up in Sources; unconfigured, they'd just be dead ends.
        link(
            stringResource(R.string.webdav),
            stringResource(R.string.webdav_subtitle),
            com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID,
        ).takeIf { webdavConfigured.isNotBlank() },
        link(
            stringResource(R.string.smb),
            stringResource(R.string.smb_subtitle),
            com.music.bitchord.data.smb.SmbConfig.BROWSE_ID,
        ).takeIf { smbHost.isNotBlank() && smbShare.isNotBlank() },
        // Only while signed in to Spotify in Settings → Accounts; it opens that
        // account's playlists rather than a browse page.
        link(
            stringResource(R.string.spotify),
            stringResource(R.string.spotify_library_subtitle),
            SPOTIFY_BROWSE_ID,
        ).takeIf { spotifyConnected.isNotBlank() }?.copy(logo = Res.drawable.spotify_logo),
    )
}

/**
 * The phone's "On device" shelf: the playlists and albums downloaded whole —
 * the same promise the folders above it make, here, now, without a network.
 * Nothing is truncated: the shelf is a row that scrolls, so "all of them"
 * costs nothing.
 */
@Composable
fun libraryDeviceItems(downloadedReleases: List<SavedCollection>): List<ShelfItem> {
    val downloadedPlaylist = stringResource(R.string.downloaded_playlist)
    return downloadedReleases.map { release ->
        ShelfItem(
            title = release.title,
            // The credit the release was downloaded with, because this is also
            // what the page it opens bills itself by — see `headerLines`, which
            // reads the kind and the owner back out of it. Saying "Downloaded
            // playlist" here instead would make that header read "Downloaded
            // playlist" over "PLAYLIST • 12 SONGS", and the shelf this card is on
            // already says where it lives.
            subtitle = release.subtitle.ifBlank { if (release.playlist) downloadedPlaylist else "" },
            thumbnailUrl = release.thumbnailUrl,
            videoId = null,
            browseId = Downloads.pageIdFor(release.id),
        )
    }
}

/**
 * The glyph a device folder is drawn with — its row on the Library page, and
 * the placeholder cover its own page opens on. Null for anything that isn't
 * one of the folders.
 */
fun deviceFolderIcon(browseId: String): ImageVector? = when (browseId) {
    "local:downloads" -> Icons.Rounded.Download
    "local:all" -> Icons.Rounded.Folder
    CACHE_FOLDER_BROWSE_ID -> Icons.Rounded.Storage
    com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID -> Icons.Rounded.Cloud
    com.music.bitchord.data.smb.SmbConfig.BROWSE_ID -> Icons.Rounded.Lan
    else -> null
}

/** The Library row that opens the connected Spotify account; handled by the app, not a browse page. */
const val SPOTIFY_BROWSE_ID = "app:spotify"

/** The Cached songs folder's page id — one of the `local:` device folders. */
const val CACHE_FOLDER_BROWSE_ID = "local:cache"

/**
 * The phone's way in to Replay plus Android-owned canonical library entries.
 *
 * OpenSubsonic lives here rather than in the shared [LibraryLink] route because
 * that route carries a legacy ShelfItem into MainActivity/openDetail. This
 * keeps the new browse independent of ShelfItem.videoId while placing the entry
 * directly beside the Library's existing folders on screen.
 */
@Composable
fun LibraryReplayEntry(
    cards: List<ReplayHeroCard>,
    loading: Boolean,
    holder: String,
    memberSince: String?,
    onOpenReplay: (ReplayStoryPage) -> Unit,
) {
    Column {
        if (loading) {
            ReplayCardRowSkeleton()
        } else if (cards.isNotEmpty()) {
            ReplayCardRow(
                cards = cards,
                holder = holder,
                memberSince = memberSince,
                onCardClick = onOpenReplay,
                modifier = Modifier.padding(vertical = 6.dp),
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            )
        }
        OpenSubsonicCanonicalLibraryEntry()
    }
}

@Composable
private fun OpenSubsonicCanonicalLibraryEntry() {
    val context = LocalContext.current
    val persistence = remember(context.applicationContext) {
        SharedPrefsOpenSubsonicAccountPersistence(context.applicationContext)
    }
    val accountFlow = remember(persistence) { persistence.observe() }
    val account by accountFlow.collectAsStateWithLifecycle(initialValue = persistence.read())
    var browseOpen by rememberSaveable { mutableStateOf(false) }

    val configured = account ?: return
    val title = stringResource(R.string.opensubsonic_title)
    val subtitle = configured.lastSyncSummary?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.remote_library_browse_subtitle)

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .clickable { browseOpen = true }
                .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = BitChordIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
                modifier = Modifier.size(20.dp),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = PAGE_GUTTER + 40.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    if (browseOpen) {
        Dialog(
            onDismissRequest = { browseOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            CanonicalRemoteLibraryScreen(
                providerKey = "opensubsonic:${configured.accountId}",
                title = title,
                onClose = { browseOpen = false },
            )
        }
    }
}
