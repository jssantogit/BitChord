package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Guard the unified new-playlist visual language without depending on a screenshot. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistEditorPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactEditorActionsUseTheGlobal40DpCircularControl() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardCompactAction(
                    label = "Opções",
                    icon = PlaylistEditorIcons.More,
                    onClick = { clicks++ },
                )
            }
        }
        compose.onNodeWithTag("playlist-editor-icon-action").assertWidthIsEqualTo(40.dp)
        compose.onNodeWithTag("playlist-editor-icon-action").assertHeightIsEqualTo(40.dp)
        compose.onNodeWithContentDescription("Opções").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun ruleCategoriesUseCompactNeutralNativeBadgesAndPreserveNavigation() {
        var clicked = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardNavigationList(
                    listOf(
                        PlaylistWizardNavOption("Música", "Condições de música", PlaylistEditorIcons.Music) { clicked++ },
                        PlaylistWizardNavOption("Datas", "Condições de data", PlaylistEditorIcons.Calendar) { clicked++ },
                    ),
                )
            }
        }
        compose.onAllNodesWithTag("playlist-category-icon-badge", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithTag("playlist-category-icon-badge", useUnmergedTree = true)[0].assertWidthIsEqualTo(38.dp)
        compose.onNodeWithText("Música").performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }
}
