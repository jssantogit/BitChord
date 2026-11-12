package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Keep Settings secondary actions accessible without generic M3 outlined controls. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeSettingsSecondaryActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activeActionHas48DpTargetAndInvokesCallbackOnlyOnce() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                NativeSettingsSecondaryAction(
                    label = "Testar conexão",
                    onClick = { calls++ },
                )
            }
        }
        compose.onNodeWithTag("native-settings-secondary-action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Testar conexão").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun disabledActionCannotRequestCredentialRemoval() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                NativeSettingsSecondaryAction(
                    label = "Desconectar e esquecer credenciais",
                    enabled = false,
                    onClick = { calls++ },
                )
            }
        }
        compose.onNodeWithTag("native-settings-secondary-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
