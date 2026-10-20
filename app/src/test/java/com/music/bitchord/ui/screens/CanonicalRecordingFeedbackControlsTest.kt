package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
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

    @Test fun feedbackDoesNotWriteUntilExplicitUserAction() {
        val values = mutableListOf<Double>()
        val clears = mutableListOf<Unit>()
        compose.setContent {
            CanonicalRecordingFeedbackPanel(
                rating = null, onSetRating = { values += it }, onClear = { clears += Unit },
            )
        }
        compose.runOnIdle { assertEquals(emptyList<Double>(), values) }
        compose.onNodeWithText("Edit rating").performClick()
        compose.runOnIdle { assertEquals(emptyList<Double>(), values) }
        compose.onNodeWithText("5.0").performClick()
        compose.runOnIdle { assertEquals(listOf(5.0), values) }
        compose.runOnIdle { assertEquals(0, clears.size) }
    }

    @Test fun explicitClearAndRerateRemainSeparateActions() {
        val values = mutableListOf<Double>()
        var clears = 0
        compose.setContent {
            CanonicalRecordingFeedbackPanel(
                rating = 4.5, onSetRating = { values += it }, onClear = { clears++ },
            )
        }
        compose.onNodeWithText("Rating: 4.5").assertExists()
        compose.onNodeWithText("Edit rating").performClick()
        compose.onNodeWithText("Clear rating").performClick()
        compose.runOnIdle {
            assertEquals(1, clears)
            assertEquals(emptyList<Double>(), values)
        }
    }
}
