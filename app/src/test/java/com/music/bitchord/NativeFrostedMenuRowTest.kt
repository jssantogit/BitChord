package com.music.bitchord

import android.app.Application
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Selected source/sort menu rows announce state and preserve callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeFrostedMenuRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedFilterIsDistinguishableAndCanStillBeClicked() {
        var clicks by mutableIntStateOf(0)
        compose.setContent {
            BitChordTheme {
                FrostedMenuRow(label = "Fonte local", checked = true, onClick = { clicks++ })
            }
        }
        compose.onNodeWithText("Fonte local").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun ordinaryMenuActionIsNotIncorrectlyMarkedSelected() {
        compose.setContent {
            BitChordTheme {
                FrostedMenuRow(label = "Visualizar em grade", checked = false, onClick = {})
            }
        }
        compose.onNodeWithText("Visualizar em grade").assertIsNotSelected()
    }
}
