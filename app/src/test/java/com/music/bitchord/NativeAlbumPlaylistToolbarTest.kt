package com.music.bitchord

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.ui.screens.PlaylistLibrarySort
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Album toolbar replaces PlaylistAdd with the actual rating action. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeAlbumPlaylistToolbarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collectionOverflowReusesTheSamePlainVectorAsTheOtherThreeDotMenus() {
        assertSame(
            com.music.bitchord.ui.icons.PlaylistEditorIcons.More,
            nativeCollectionOverflowGlyph(),
        )
    }

    @Test fun publicPlaylistSortAndOverflowAreAnchoredToTheirOwnToolbarButtons() {
        val playlist = ListenBrainzPublicPlaylist(
            mbid = "66dd7663-76a4-4e31-93ae-730ccd49d7d8",
            title = "Public Playlist", creator = "Author", description = null,
        )
        val sortOpen = mutableStateOf(false)
        val optionsOpen = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Row {
                    NativePublicPlaylistTopBarActions(
                    playlist = playlist, searching = false,
                    onToggleSearch = {},
                    sort = PlaylistLibrarySort.DEFAULT, onSort = {},
                    sortOpen = sortOpen.value, optionsOpen = optionsOpen.value,
                    onShowSort = { sortOpen.value = true },
                    onShowOptions = { optionsOpen.value = true },
                    onSortDismiss = { sortOpen.value = false },
                    onOptionsDismiss = { optionsOpen.value = false },
                    )
                }
            }
        }
        compose.onNodeWithTag("native-toolbar-sort-anchor").assertExists()
        compose.onNodeWithTag("native-toolbar-overflow-anchor").assertExists()
        compose.onNodeWithContentDescription("Ordenar músicas").assertWidthIsEqualTo(40.dp)
        compose.onNodeWithContentDescription("Opções da playlist")
            .assertWidthIsEqualTo(40.dp).performClick()
        compose.onNodeWithTag("native-compact-overflow").assertExists()
        compose.onNodeWithText("Compartilhar playlist").assertExists()
    }

    @Test fun nativeReleaseRatingButtonUsesExistingCanonicalEditorCallback() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                NativeCollectionRatingToolbarAction(
                    rating = 4.5, subject = "álbum", onClick = { taps++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Avaliar álbum", substring = true)
            .assertIsDisplayed().assertWidthIsEqualTo(40.dp).performClick()
        compose.onNodeWithContentDescription("4,5 de 5 estrelas", substring = true)
            .assertExists()
        compose.onNodeWithContentDescription("Adicionar álbum à playlist")
            .assertDoesNotExist()
        assertEquals(1, taps)
    }

    @Test fun unratedPlaylistIsActionableWithoutFabricatingAScore() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                NativeCollectionRatingToolbarAction(
                    rating = null, subject = "playlist", onClick = { taps++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Avaliar playlist", substring = true)
            .performClick()
        compose.onNodeWithContentDescription("5 de 5 estrelas", substring = true)
            .assertDoesNotExist()
        assertEquals(1, taps)
    }
    @Test fun librarySortPopupUsesSharedCompactMenuAndPreservesSelection() {
        val expanded = mutableStateOf(false)
        val selected = mutableStateOf(LibrarySort.DEFAULT)
        val ascendingLabel = org.robolectric.RuntimeEnvironment.getApplication()
            .getString(R.string.sort_title_ascending)
        compose.setContent {
            MaterialTheme {
                NativeLibraryTopBarSortAction(
                    expanded = expanded.value,
                    current = selected.value,
                    onOpen = { expanded.value = true },
                    onClose = { expanded.value = false },
                    onChange = { option ->
                        selected.value = option
                        expanded.value = false
                    },
                )
            }
        }
        compose.onNodeWithTag("native-toolbar-library-sort-trigger")
            .assertWidthIsEqualTo(40.dp).performClick()
        compose.onNodeWithTag("native-compact-overflow").assertExists()
        compose.onNodeWithText(ascendingLabel).performClick()
        compose.runOnIdle { assertEquals(LibrarySort.TITLE_ASC, selected.value) }
        compose.waitForIdle()
        compose.onNodeWithTag("native-toolbar-library-sort-trigger").performClick()
        compose.onNodeWithText(ascendingLabel).assertIsSelected()
    }

}
