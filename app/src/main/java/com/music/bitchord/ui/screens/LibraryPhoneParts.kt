package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.music.bitchord.ui.replay.ReplayCardRow
import com.music.bitchord.ui.replay.ReplayHeroCard
import com.music.bitchord.ui.replay.ReplayStoryPage

// The phone's halves of the shared Library page: its list of folders, what
// sits on its "On device" shelf, and the way in to Replay at its head.

/**
 * The phone's Library links. Legacy folders still carry a ShelfItem browse id;
 * the canonical OpenSubsonic entry uses LibraryLink.onClick instead, so it can
 * enter the Room-backed browse without manufacturing a browse/video id.
 */
@Composable
fun libraryLinks(): List<LibraryLink> {
    val context = LocalContext.current
    val webdavConfigured by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val smbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val spotifyConnected by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()

    val openSubsonicPersistence = remember(context.applicationContext) {
        SharedPrefsOpenSubsonicAccountPersistence(context.applicationContext)
    }
    val openSubsonicAccountFlow = remember(openSubsonicPersistence) {
        openSubsonicPersistence.observe()
    }
    val openSubsonicAccount by openSubsonicAccountFlow.collectAsStateWithLifecycle(
        initialValue = openSubsonicPersistence.read(),
    )
    var remoteBrowseOpen by rememberSaveable { mutableStateOf(false) }

    val openSubsonicTitle = stringResource(R.string.opensubsonic_title)
    val configuredOpenSubsonic = openSubsonicAccount
    if (remoteBrowseOpen && configuredOpenSubsonic != null) {
        Dialog(
            onDismissRequest = { remoteBrowseOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            CanonicalRemoteLibraryScreen(
                providerKey = "opensubsonic:${configuredOpenSubsonic.accountId}",
                title = openSubsonicTitle,
                onClose = { remoteBrowseOpen = false },
            )
        }
    }

    fun link(
        title: String,
        subtitle: String,
        browseId: String,
        icon: ImageVector = deviceFolderIcon(browseId) ?: Icons.Rounded.Cloud,
    ) = LibraryLink(
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
        // The canonical remote library has no legacy browseId/videoId at all.
        // Its callback opens a Room-only browse screen directly.
        configuredOpenSubsonic?.let { account ->
            LibraryLink(
                item = ShelfItem(
                    title = openSubsonicTitle,
                    subtitle = account.lastSyncSummary?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.remote_library_browse_subtitle),
                    thumbnailUrl = null,
                    videoId = null,
                    browseId = null,
                ),
                icon = Icons.Rounded.Cloud,
                onClick = { remoteBrowseOpen = true },
            )
        },
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
 * Main Library uses the existing folder navigation contract and the existing
 * LocalFolder tab state. Categories are routes, not one-off playlist shelves.
 * The standalone playlist page owns its own saved collection list.
 */
fun libraryNavigationLinks(
    deviceLinks: List<LibraryLink>,
    onOpenPlaylists: () -> Unit,
    onOpenRatings: () -> Unit,
    onOpenLocalCategory: (LocalFolderTab) -> Unit,
): List<LibraryLink> {
    fun destination(title: String, icon: ImageVector, onClick: () -> Unit) =
        LibraryLink(
            item = ShelfItem(
                title = title, subtitle = "", thumbnailUrl = null,
                browseId = null, videoId = null,
            ),
            icon = icon,
            onClick = onClick,
        )
    val radios = destination("Rádios", Icons.Rounded.Radio) { }.copy(enabled = false)
    return listOf(
        destination("Playlists", Icons.Rounded.QueueMusic, onOpenPlaylists),
        destination("Artistas", Icons.Rounded.Person) {
            onOpenLocalCategory(LocalFolderTab.ARTISTS)
        },
        destination("Álbuns", Icons.Rounded.Album) {
            onOpenLocalCategory(LocalFolderTab.ALBUMS)
        },
        destination("Músicas", Icons.Rounded.MusicNote) {
            onOpenLocalCategory(LocalFolderTab.SONGS)
        },
        radios,
        destination("Minhas avaliações", Icons.Rounded.Star, onOpenRatings),
    ) + deviceLinks.filter { it.item.browseId == "local:downloads" }
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
 * The phone's way in to Replay: its row of headline cards, placeholders for
 * them while the history is read, or nothing at all while there is not yet
 * enough listening to deal them — Settings still opens Replay either way.
 */
@Composable
fun LibraryReplayEntry(
    cards: List<ReplayHeroCard>,
    loading: Boolean,
    holder: String,
    memberSince: String?,
    onOpenReplay: (ReplayStoryPage) -> Unit,
) {
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
}
