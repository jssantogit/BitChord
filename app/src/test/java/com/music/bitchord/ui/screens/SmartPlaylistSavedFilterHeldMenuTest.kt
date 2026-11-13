package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.music.bitchord.ui.components.LongPressOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SmartPlaylistSavedFilterHeldMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedFilterHoldCapturesArtworkWithoutOpeningEditor() {
        var opened = 0
        var holds = 0
        var captured = false
        compose.setContent {
            MaterialTheme {
                SettingsRow(
                    title = "Meu filtro",
                    subtitle = "2 condições · Filtro reutilizável",
                    onClick = { opened++ },
                    onLongPress = {
                        holds++
                        captured = LongPressOrigin.consume() != null
                    },
                    modifier = Modifier.testTag("held-filter-test-row"),
                )
            }
        }
        compose.onNodeWithTag("held-filter-test-row").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, holds)
            assertEquals(0, opened)
            assertTrue(captured)
        }
    }

    @Test fun heldActionsIncludeEditAndKeepFilterApplication() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SavedFilterMenuActions(
                    options = savedFilterActionsForDraft(false, null),
                    onChoose = { chosen += it },
                )
            }
        }
        compose.onNodeWithText("Editar filtro").performClick()
        compose.onNodeWithText("Aplicar regras").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Editar filtro", "Aplicar regras ao rascunho"), chosen)
        }
    }
}
