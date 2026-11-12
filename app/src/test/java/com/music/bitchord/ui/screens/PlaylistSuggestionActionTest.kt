package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Playlist suggestions must use the app's standard 40dp actionable puck. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistSuggestionActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun refreshActionIs40dpAndPreservesCallback() {
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                PlaylistSuggestionAction(
                    icon = Icons.Rounded.Refresh,
                    label = "Atualizar sugestões",
                    onClick = { refreshes++ },
                )
            }
        }
        compose.onNodeWithTag("playlist-suggestion-action")
            .assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        compose.onNodeWithContentDescription("Atualizar sugestões").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun disabledActionDoesNotAdvanceTheSuggestionPage() {
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                PlaylistSuggestionAction(
                    icon = Icons.Rounded.Refresh,
                    label = "Atualizar sugestões",
                    enabled = false,
                    onClick = { refreshes++ },
                )
            }
        }
        compose.onNodeWithTag("playlist-suggestion-action")
            .assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        compose.runOnIdle { assertEquals(0, refreshes) }
    }
}
