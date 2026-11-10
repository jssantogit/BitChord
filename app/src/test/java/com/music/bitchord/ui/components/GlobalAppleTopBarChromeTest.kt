package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.R
import com.music.bitchord.ui.theme.BitChordTheme
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
                    IconButton(onClick = {}) { Icon(Icons.Rounded.Sort, contentDescription = "Sort") }
                }
            }
        }
        compose.onAllNodesWithTag("apple-toolbar-action-circle", useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onNodeWithContentDescription("Search").assertExists()
        compose.onNodeWithContentDescription("Sort").assertExists()
    }
}
