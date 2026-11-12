package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
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

/** One 40dp multi-selection control across rows and media cards. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeLibrarySelectionActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun uncheckedControlHas40DpBoundsAndTogglesItsOriginalCallback() {
        var toggles = 0
        compose.setContent {
            MaterialTheme {
                NativeLibrarySelectionAction(
                    selected = false,
                    label = "Selecionar faixa",
                    onToggle = { toggles++ },
                )
            }
        }
        compose.onNodeWithTag("native-library-selection-action")
            .assertWidthIsEqualTo(40.dp)
            .assertHeightIsEqualTo(40.dp)
            .assertIsOff()
            .performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
    }

    @Test fun checkedControlAnnouncesSelectedStateAndCanBeUnselected() {
        var toggles = 0
        compose.setContent {
            MaterialTheme {
                NativeLibrarySelectionAction(
                    selected = true,
                    label = "Desmarcar álbum",
                    onToggle = { toggles++ },
                )
            }
        }
        compose.onNodeWithTag("native-library-selection-action").assertIsOn().performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
    }
}
