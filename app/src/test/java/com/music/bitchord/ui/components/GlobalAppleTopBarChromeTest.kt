package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.R
import com.music.bitchord.ui.theme.BitChordTheme
import com.music.bitchord.ui.screens.CircleIconButton
import com.music.bitchord.ui.screens.ActionPill
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Global Apple-style chrome contracts. Profile/settings access is never lost,
 * and multiple top-bar actions must have separate circular surfaces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class GlobalAppleTopBarChromeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accountAvatarBecomesThreeDotSettingsMenu() {
        var enteredSettings = false
        compose.setContent {
            BitChordTheme {
                TopBarAccountButton(account = null, onClick = { enteredSettings = true })
            }
        }
        val res = RuntimeEnvironment.getApplication().resources
        compose.onNodeWithContentDescription(res.getString(R.string.more)).performClick()
        compose.onNodeWithText(res.getString(R.string.settings)).performClick()
        compose.runOnIdle { assertTrue(enteredSettings) }
    }

    @Test fun globalTopBarActionsHaveIndependentCircleSurfaces() {
        compose.setContent {
            BitChordTheme {
                ArtworkPageActions(hazeState = null) {
                    IconButton(onClick = {}) { Icon(Icons.Rounded.Search, contentDescription = "Search") }
                    // AnimatedVisibility and other absent toolbar slots must not
                    // leave an empty glass circle behind.
                    Spacer(Modifier.size(0.dp))
                    // A compact non-icon indicator is not a toolbar action.
                    Spacer(Modifier.size(20.dp))
                    IconButton(onClick = {}) { Icon(Icons.Rounded.Sort, contentDescription = "Sort") }
                }
            }
        }
        compose.onAllNodesWithTag("apple-toolbar-action-circle", useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onNodeWithContentDescription("Search").assertExists()
        compose.onNodeWithContentDescription("Sort").assertExists()
    }
    @Test fun threeIconToolbarUsesFullTapTargetsAtNarrowAvailableWidth() {
        compose.setContent {
            BitChordTheme {
                ArtworkPageActions(
                    hazeState = null,
                    modifier = Modifier.width(152.dp).testTag("narrow-action-toolbar"),
                ) {
                    listOf("One", "Two", "Three").forEach { label ->
                        IconButton(onClick = {}) {
                            Icon(Icons.Rounded.Sort, contentDescription = label)
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("narrow-action-toolbar", useUnmergedTree = true)
            .assertWidthIsEqualTo(152.dp)
        compose.onAllNodesWithTag("apple-toolbar-action-circle", useUnmergedTree = true)
            .assertCountEquals(3)
    }

    @Test fun unavailableSecondaryActionsRemainDisabledWithSharedSurface() {
        compose.setContent {
            BitChordTheme {
                CircleIconButton(
                    icon = Icons.Rounded.Search,
                    contentDescription = "Unavailable circle",
                    onClick = {},
                    enabled = false,
                )
                ActionPill(
                    icon = Icons.Rounded.Search,
                    label = "Unavailable labeled action",
                    onClick = {},
                    filled = false,
                    enabled = false,
                )
            }
        }
        compose.onNodeWithContentDescription("Unavailable circle").assertIsNotEnabled()
        compose.onNodeWithText("Unavailable labeled action").assertIsNotEnabled()
    }

}
