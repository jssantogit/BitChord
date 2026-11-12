package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativePrimaryActionColorsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedNativePrimaryActionStillProvidesAnAccessibleClickableControl() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                Button(
                    onClick = { taps++ },
                    modifier = Modifier.height(52.dp).testTag("native-primary-action"),
                    colors = applePrimaryButtonColors(),
                ) { Text("Concluir") }
            }
        }
        compose.onNodeWithTag("native-primary-action")
            .assertHeightIsEqualTo(52.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun sharedMessageStateActionUsesFiftyDpNativeControl() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                MessageState("Falha de carregamento", actionLabel = "Tentar novamente", onAction = { retries++ })
            }
        }
        compose.onNodeWithTag("native-message-action")
            .assertHeightIsEqualTo(50.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
