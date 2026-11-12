package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
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

/** The party-size stepper shares the app's 40dp native action geometry. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ListenTogetherStepperPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enabledStepperUses40dpAndKeepsItsCallback() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                PartyStepperControl(label = "+", enabled = true, onClick = { clicks++ })
            }
        }
        compose.onNodeWithTag("listen-together-party-stepper")
            .assertWidthIsEqualTo(40.dp)
            .assertHeightIsEqualTo(40.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun stepperAtItsBoundaryIsDisabled() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                PartyStepperControl(label = "−", enabled = false, onClick = { clicks++ })
            }
        }
        compose.onNodeWithTag("listen-together-party-stepper").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, clicks) }
    }
}
