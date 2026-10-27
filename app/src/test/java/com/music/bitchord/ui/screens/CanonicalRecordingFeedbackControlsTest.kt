package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class CanonicalRecordingFeedbackControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingStarsDoesNotSaveUntilConfirmed() {
        val values = mutableListOf<Double>()
        val clears = mutableListOf<Unit>()
        compose.setContent {
            CanonicalRecordingFeedbackPanel(
                rating = null, onSetRating = { values += it }, onClear = { clears += Unit },
            )
        }
        compose.onNodeWithText("Edit rating").performClick()
        compose.onNodeWithContentDescription("5 stars").performClick()
        compose.runOnIdle { assertEquals(emptyList<Double>(), values) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf(5.0), values)
            assertEquals(0, clears.size)
        }
    }

    @Test fun halfStarsAreSelectableAndClearRatingIsNotShown() {
        val values = mutableListOf<Double>()
        var clears = 0
        compose.setContent {
            CanonicalRecordingFeedbackPanel(
                rating = 4.5, onSetRating = { values += it }, onClear = { clears++ },
            )
        }
        compose.onNodeWithText("Clear rating").assertDoesNotExist()
        compose.onNodeWithText("Edit rating").performClick()
        compose.onNodeWithContentDescription("4.5 stars").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf(4.5), values)
            assertEquals(0, clears)
        }
    }
}
