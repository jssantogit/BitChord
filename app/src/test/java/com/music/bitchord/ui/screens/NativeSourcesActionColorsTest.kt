package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.R
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Theme-blue add-source action keeps its accessible Settings-row callback. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeSourcesActionColorsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addSourceRowStillOpensEditorFromItsAccessibleTitle() {
        var clicks = 0
        compose.setContent {
            BitChordTheme { AddSourceRow(onClick = { clicks++ }) }
        }
        val title = RuntimeEnvironment.getApplication().getString(R.string.add_addon)
        compose.onNodeWithText(title).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
