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

    @Test fun selectionAndFilterCardsShareOneNeutralSurfacePolicy() {
        assertEquals(
            com.music.bitchord.ui.components.nativeCompactPlateColor(true, null),
            com.music.bitchord.ui.components.nativeContextPlateColor(true),
        )
        assertEquals(
            com.music.bitchord.ui.components.nativeCompactPlateColor(false, null),
            com.music.bitchord.ui.components.nativeContextPlateColor(false),
        )
        assertEquals(false, com.music.bitchord.ui.components.expandableActionCardUsesHaze(
            treatment = com.music.bitchord.ui.components.NativeActionCardTreatment.SOLID,
            hazeAvailable = true, reduceBlur = false,
        ))
        assertEquals(true, com.music.bitchord.ui.components.expandableActionCardUsesHaze(
            treatment = com.music.bitchord.ui.components.NativeActionCardTreatment.FROSTED,
            hazeAvailable = true, reduceBlur = false,
        ))
        assertEquals(false, com.music.bitchord.ui.components.expandableActionCardUsesHaze(
            treatment = com.music.bitchord.ui.components.NativeActionCardTreatment.FROSTED,
            hazeAvailable = true, reduceBlur = true,
        ))
    }

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
