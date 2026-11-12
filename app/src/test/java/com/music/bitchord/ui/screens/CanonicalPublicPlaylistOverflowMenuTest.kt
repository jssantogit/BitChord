package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalPublicPlaylistOverflowMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun publicPlaylistOverflowUsesCompactPopupWithoutLosingShare() {
        compose.setContent {
            MaterialTheme {
                CanonicalPublicPlaylistOverflowMenu(
                    playlist = ListenBrainzPublicPlaylist(
                        mbid = "66dd7663-76a4-4e31-93ae-730ccd49d7d8",
                        title = "Test Playlist", creator = "Author", description = null,
                    ),
                    expanded = true, onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("native-compact-overflow").assertExists()
        compose.onNodeWithText("Compartilhar playlist").assertExists()
    }
    @Test fun publicPlaylistSortUsesSharedCompactPopupAndRestoresSelectedChoice() {
        val current = mutableStateOf(PlaylistLibrarySort.DEFAULT)
        val expanded = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CanonicalPublicPlaylistSortMenu(
                    expanded = expanded.value,
                    onDismiss = { expanded.value = false },
                    sort = current.value,
                    onSort = { current.value = it },
                )
            }
        }
        compose.onNodeWithTag("native-compact-overflow").assertExists()
        compose.onNodeWithText("Título (A–Z)").performClick()
        compose.runOnIdle { assertEquals(PlaylistLibrarySort.TITLE_ASC, current.value) }
        compose.runOnIdle { expanded.value = true }
        compose.onNodeWithText("Título (A–Z)").assertIsSelected()
    }

}
