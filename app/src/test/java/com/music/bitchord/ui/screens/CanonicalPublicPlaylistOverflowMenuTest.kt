package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
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
                CanonicalPublicPlaylistToolbarMenus(
                    playlist = ListenBrainzPublicPlaylist(
                        mbid = "66dd7663-76a4-4e31-93ae-730ccd49d7d8",
                        title = "Test Playlist", creator = "Author", description = null,
                    ),
                    sortOpen = false, optionsOpen = true,
                    onSortDismiss = {}, onOptionsDismiss = {},
                    sort = PlaylistLibrarySort.DEFAULT, onSort = {},
                )
            }
        }
        compose.onNodeWithTag("native-compact-overflow").assertExists()
        compose.onNodeWithText("Compartilhar playlist").assertExists()
    }
}
