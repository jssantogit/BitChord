package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
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
class CanonicalLibraryBatchActionBarStyleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionUsesLiftedContextPlateAndStillCloses() {
        var closed = 0
        compose.setContent {
            MaterialTheme {
                CanonicalLibraryBatchActionBar(
                    selectedEntityCount = 1,
                    summary = "5 faixas · 1 item",
                    onClear = { closed++ },
                    onAddToPlaylist = {},
                    onPlayNext = {},
                    onAddToQueue = {},
                )
            }
        }
        compose.onNodeWithTag("native-selection-held-card").assertExists()
        compose.onNodeWithContentDescription("Encerrar seleção").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }
}
