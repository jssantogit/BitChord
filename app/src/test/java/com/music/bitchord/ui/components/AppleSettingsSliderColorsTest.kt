package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Blue theme roles must not break the six continuous Settings controls. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppleSettingsSliderColorsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeSettingsSliderRetainsAccessibilityAndProgressCallback() {
        var value by mutableFloatStateOf(0.2f)
        compose.setContent {
            MaterialTheme {
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    colors = appleSettingsSliderColors(),
                    modifier = Modifier.testTag("native-settings-slider"),
                )
            }
        }
        compose.onNodeWithTag("native-settings-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(0.8f) }
        compose.runOnIdle { assertEquals(0.8f, value, 0.01f) }
    }
}
