package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Native status fallback for a Song with no verified RecordingId. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeStatusAlertTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingStateKeepsSpinnerAndCancelWithoutPrematureUnavailableText() {
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                NativeStatusAlert(
                    hazeState = null,
                    title = "Avaliar música",
                    description = "Indisponível",
                    loading = true,
                    dismissLabel = "Cancelar",
                    onDismiss = { dismissals++ },
                )
            }
        }
        compose.onNodeWithTag("native-status-alert-loading").assertExists()
        compose.onNodeWithText("Indisponível").assertDoesNotExist()
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun unresolvedIdentityShowsUnavailableWithOnlyDismissAction() {
        compose.setContent {
            MaterialTheme {
                NativeStatusAlert(
                    hazeState = null,
                    title = "Avaliar música",
                    description = "Indisponível",
                    loading = false,
                    dismissLabel = "Cancelar",
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("native-status-alert-loading").assertDoesNotExist()
        compose.onNodeWithText("Indisponível").assertExists()
        compose.onNodeWithText("Cancelar").assertExists()
    }
}
