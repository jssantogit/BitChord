package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
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
class PlaylistSavedFilterHoldComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedFilterHoldOpensActionsWithoutNavigatingToEditor() {
        var edits = 0
        var menus = 0
        compose.setContent {
            MaterialTheme {
                SettingsRow(
                    title = "kk", subtitle = "1 condição · Filtro reutilizável",
                    onClick = { edits++ },
                    onLongPress = { menus++ },
                    reserveIconSpace = false,
                    trailing = {},
                    modifier = Modifier.testTag("saved-filter-hold-test"),
                )
            }
        }
        compose.onNodeWithTag("saved-filter-hold-test")
            .performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, menus)
            assertEquals(0, edits)
        }
        compose.onNodeWithTag("saved-filter-hold-test").performClick()
        compose.runOnIdle {
            assertEquals(1, menus)
            assertEquals(1, edits)
        }
    }
}
