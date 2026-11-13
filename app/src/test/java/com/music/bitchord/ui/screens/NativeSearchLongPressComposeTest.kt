package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeSearchLongPressComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPressOnSearchHitOpensItsExistingThreeDotActions() {
        var details = 0
        compose.setContent {
            MaterialTheme {
                NativeSearchResultRow(
                    row = NativeSearchRowUi(
                        title = "Exemplo", subtitle = "Artista",
                        artworkUri = null, artistPortrait = false,
                    ),
                    onClick = {},
                    onMoreActions = listOf(
                        NativeSearchRowAction("Ver detalhes") { details++ },
                    ),
                )
            }
        }
        compose.onNodeWithTag("search-row-more-actions").assertDoesNotExist()
        compose.onNodeWithTag("native-search-result-row")
            .performTouchInput { longClick() }
        compose.onNodeWithTag("native-compact-overflow").assertExists()
        compose.onNodeWithTag("native-compact-menu-item").assertExists()
        compose.onNodeWithText("Ver detalhes").assertExists()
        compose.onNodeWithText("Ver detalhes").performClick()
        compose.runOnIdle { assertEquals(1, details) }
    }

    @Test fun metadataOnlyHitWithNoPlayClickStillSupportsContextActions() {
        compose.setContent {
            MaterialTheme {
                NativeSearchResultRow(
                    row = NativeSearchRowUi(
                        title = "Gravação", subtitle = "MusicBrainz",
                        artworkUri = null, artistPortrait = false,
                    ),
                    onClick = null,
                    onMoreActions = listOf(
                        NativeSearchRowAction("Ver detalhes") {},
                    ),
                )
            }
        }
        compose.onNodeWithTag("native-search-result-row")
            .performTouchInput { longClick() }
        compose.onNodeWithText("Ver detalhes").assertExists()
    }
}
