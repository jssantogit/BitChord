package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** A native selected color does not interfere with the Settings switch contract. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppleSettingsSwitchColorsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeStyledSettingsSwitchPreservesToggleSemanticsAndCallbacks() {
        var checked by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Switch(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    modifier = Modifier.testTag("native-settings-switch"),
                    colors = appleSettingsSwitchColors(),
                )
            }
        }
        compose.onNodeWithTag("native-settings-switch").performClick().assertIsOn()
    }
}
