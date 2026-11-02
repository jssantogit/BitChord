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
            deviceLinks = listOf(
                deviceLink("Downloads", "local:downloads"),
                deviceLink("Música local", "local:all"),
            ),
            onOpenPlaylists = {},
            onOpenLocalCategory = {},
        )
        assertEquals(
            listOf(
                "Playlists", "Artistas", "Álbuns", "Músicas",
                "Rádios", "Downloads",
            ),
            result.map { it.item.title },
        )
        assertFalse(result[4].enabled)
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
