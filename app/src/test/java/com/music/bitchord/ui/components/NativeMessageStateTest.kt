package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeMessageStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retryErrorActionIsNative50DpAndInvokesCallbackOnce() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                MessageState(
                    message = "Sem conexão",
                    actionLabel = "Tentar novamente",
                    onAction = { retries++ },
                )
            }
        }
        compose.onNodeWithText("Sem conexão").assertExists()
        compose.onNodeWithTag("native-message-action").assertHeightIsEqualTo(50.dp)
        compose.onNodeWithText("Tentar novamente").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun messageWithoutActionDoesNotExposeDeadButton() {
        compose.setContent { MaterialTheme { MessageState(message = "Sem resultados") } }
        compose.onNodeWithTag("native-message-action").assertDoesNotExist()
    }
}
