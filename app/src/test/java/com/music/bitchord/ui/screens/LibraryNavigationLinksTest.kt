package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.LibraryViewType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibraryNavigationLinksTest {
    private fun deviceLink(title: String, browseId: String) = LibraryLink(
        item = ShelfItem(
            title = title, subtitle = "", thumbnailUrl = null,
            videoId = null, browseId = browseId,
        ),
        icon = if (browseId == "local:downloads") Icons.Rounded.Download
            else Icons.Rounded.Folder,
    )

    @Test fun categoryViewChangesDoNotLeakIntoOtherLibraryTabs() {
        val state = LocalFolderState()
        val fallback = LibraryViewType.LIST
        state.setViewTypeFor(LocalFolderTab.ARTISTS, LibraryViewType.GRID)
        assertEquals(LibraryViewType.GRID, state.viewTypeFor(LocalFolderTab.ARTISTS, fallback))
        assertEquals(fallback, state.viewTypeFor(LocalFolderTab.SONGS, fallback))
        assertEquals(fallback, state.viewTypeFor(LocalFolderTab.ALBUMS, fallback))
        state.setViewTypeFor(LocalFolderTab.ALBUMS, LibraryViewType.GRID)
        state.setViewTypeFor(LocalFolderTab.ARTISTS, LibraryViewType.LIST)
        assertEquals(LibraryViewType.LIST, state.viewTypeFor(LocalFolderTab.ARTISTS, fallback))
        assertEquals(LibraryViewType.GRID, state.viewTypeFor(LocalFolderTab.ALBUMS, fallback))
        assertEquals(fallback, state.viewTypeFor(LocalFolderTab.SONGS, fallback))
    }

    @Test fun libraryHomeIsCategoryFirstAndKeepsOnlyDownloadsFromDeviceLinks() {
        val result = libraryNavigationLinks(
            labels = LibraryCategoryLabels("Playlists", "Artistas", "Álbuns", "Músicas"),
            deviceLinks = listOf(
                deviceLink("Downloads", "local:downloads"),
                deviceLink("Música local", "local:all"),
            ),
            onOpenPlaylists = {},
            onOpenLocalCategory = {},
        )
        assertEquals(
            listOf(
                "Playlists", "Artistas", "Álbuns", "Músicas", "Downloads",
            ),
            result.map { it.item.title },
        )
        assertEquals("local:downloads", result.last().item.browseId)
        assertEquals(true, result.all { it.enabled })
    }
    @Test fun localizedCategoryNamesKeepTheSameNavigationAndDownloadRoute() {
        val destinations = mutableListOf<LocalFolderTab>()
        var playlistOpened = 0
        val result = libraryNavigationLinks(
            labels = LibraryCategoryLabels("Playlists", "Artists", "Albums", "Songs"),
            deviceLinks = listOf(deviceLink("Downloads", "local:downloads")),
            onOpenPlaylists = { playlistOpened++ },
            onOpenLocalCategory = { destinations += it },
        )
        assertEquals(listOf("Playlists", "Artists", "Albums", "Songs", "Downloads"),
            result.map { it.item.title })
        result[0].onClick?.invoke()
        result[1].onClick?.invoke()
        result[2].onClick?.invoke()
        result[3].onClick?.invoke()
        assertEquals(1, playlistOpened)
        assertEquals(listOf(LocalFolderTab.ARTISTS, LocalFolderTab.ALBUMS, LocalFolderTab.SONGS), destinations)
        assertEquals("local:downloads", result.last().item.browseId)
    }

    @Test fun unifiedLibraryRequestsMissingAudioPermissionWithoutBlockingNavigation() {
        val transitions = mutableListOf<String>()
        openUnifiedLibraryCategory(
            hasMediaPermission = false,
            onOpenCategory = { transitions += "opened" },
            onRequestPermission = { transitions += "requested" },
        )
        assertEquals(listOf("opened", "requested"), transitions)
    }

    @Test fun unifiedLibraryDoesNotPromptWhenLocalAudioAlreadyGranted() {
        val transitions = mutableListOf<String>()
        openUnifiedLibraryCategory(
            hasMediaPermission = true,
            onOpenCategory = { transitions += "opened" },
            onRequestPermission = { transitions += "requested" },
        )
        assertEquals(listOf("opened"), transitions)
    }

}
