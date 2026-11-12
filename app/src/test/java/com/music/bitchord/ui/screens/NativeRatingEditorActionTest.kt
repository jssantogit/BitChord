package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
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

/** Solid rating sheet actions must retain 52dp and never submit while busy. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeRatingEditorActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun saveHas52DpHitTargetAndCallsExactlyOnce() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                NativeRatingEditorAction(
                    label = "Salvar avaliação",
                    emphasized = true,
                    darkTheme = false,
                    onClick = { saves++ },
                    tag = "rating-editor-save-action",
                )
            }
        }
        compose.onNodeWithTag("rating-editor-save-action").assertHeightIsEqualTo(52.dp)
        compose.onNodeWithText("Salvar avaliação").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun disabledSaveCannotWrite() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                NativeRatingEditorAction(
                    label = "Salvar avaliação",
                    emphasized = true,
                    darkTheme = true,
                    enabled = false,
                    onClick = { saves++ },
                    tag = "rating-editor-save-action",
                )
            }
        }
        compose.onNodeWithTag("rating-editor-save-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun busySaveDisplaysSpinnerInsteadOfTextAndCannotWrite() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                NativeRatingEditorAction(
                    label = "Salvar avaliação",
                    emphasized = true,
                    darkTheme = false,
                    loading = true,
                    enabled = false,
                    onClick = { saves++ },
                    tag = "rating-editor-save-action",
                )
            }
        }
        compose.onNodeWithText("Salvar avaliação").assertDoesNotExist()
        compose.onNodeWithTag("native-rating-action-loading", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("rating-editor-save-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
