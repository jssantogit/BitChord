package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.ui.icons.BitChordIcons
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Search pagination and highlight CTAs cannot fall back to Material 3 outlines. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeSearchSecondaryActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customSecondaryActionPreservesPlayCallback() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                NativeSearchSecondaryAction(
                    label = "Reproduzir",
                    icon = BitChordIcons.Play,
                    onClick = { clicks++ },
                )
            }
        }
        compose.onNodeWithTag("native-search-secondary-action")
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun loadingPaginationRemainsVisibleAndCannotSendDuplicateRequests() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                NativeSearchSecondaryAction(
                    label = "Carregar mais edições",
                    loading = true,
                    onClick = { clicks++ },
                )
            }
        }
        compose.onNodeWithText("Carregar mais edições").assertExists()
        compose.onNodeWithTag("native-search-secondary-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun disabledActionDoesNotInventAnAvailableDestination() {
        compose.setContent {
            MaterialTheme {
                NativeSearchSecondaryAction(
                    label = "Indisponível", enabled = false, onClick = {},
                )
            }
        }
        compose.onNodeWithTag("native-search-secondary-action").assertIsNotEnabled()
    }
}
