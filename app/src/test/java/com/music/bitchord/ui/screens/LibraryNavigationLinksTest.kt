package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import com.music.bitchord.data.model.ShelfItem
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

    @Test fun libraryHomeIsCategoryFirstAndKeepsOnlyDownloadsFromDeviceLinks() {
        val result = libraryNavigationLinks(
            deviceLinks = listOf(
                deviceLink("Downloads", "local:downloads"),
                deviceLink("Música local", "local:all"),
            ),
            onOpenPlaylists = {},
            onOpenRatings = {},
            onOpenLocalCategory = {},
        )
        assertEquals(
            listOf(
                "Playlists", "Artistas", "Álbuns", "Músicas",
                "Rádios", "Minhas avaliações", "Downloads",
            ),
            result.map { it.item.title },
        )
        assertFalse(result[4].enabled)
        assertEquals("local:downloads", result.last().item.browseId)
    }
}
