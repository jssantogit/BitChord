package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
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
class RediscoveryNativeBackActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rediscoveryBackUsesNative40dpCircleAndPreservesNavigation() {
        var navigations = 0
        compose.setContent {
            MaterialTheme {
                RediscoveryBackAction(label = "Voltar", onClick = { navigations++ })
            }
        }
        compose.onNodeWithTag("rediscovery-native-back")
            .assertWidthIsEqualTo(40.dp)
            .assertHeightIsEqualTo(40.dp)
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.runOnIdle { assertEquals(1, navigations) }
    }
}
