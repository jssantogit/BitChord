package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The selection menu expands into the native held-action language, not a
 * stale row of tiny glyphs above the bottom bar. All callbacks stay live. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalLibrarySelectionExpandedActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionActionsRespectReducedMotionWithoutHidingTheControls() {
        assertEquals(androidx.compose.animation.EnterTransition.None, selectionActionsEnterTransition(true))
        assertEquals(androidx.compose.animation.ExitTransition.None, selectionActionsExitTransition(true))
        org.junit.Assert.assertTrue(selectionActionsEnterTransition(false) != androidx.compose.animation.EnterTransition.None)
        org.junit.Assert.assertTrue(selectionActionsExitTransition(false) != androidx.compose.animation.ExitTransition.None)

        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                com.music.bitchord.ui.LocalReduceAnimations provides true,
            ) {
                MaterialTheme {
                    CanonicalLibraryBatchActionBar(
                        selectedEntityCount = 2,
                        summary = "5 faixas · 2 itens",
                        onClear = {},
                        onAddToPlaylist = {},
                        onPlayNext = {},
                        onAddToQueue = {},
                    )
                }
            }
        }
        // The bottom dock must start compact. Opening the action list is explicit.
        compose.onNodeWithTag("native-selection-expanded-actions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expandir ações").performClick()
        compose.onNodeWithTag("native-selection-expanded-actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Recolher ações").performClick()
        compose.onNodeWithTag("native-selection-expanded-actions").assertDoesNotExist()
    }

    @Test fun expandedMenuOffersNativeActionRowsAndCanCollapseWithoutLosingSelection() {
        var queued = 0
        compose.setContent {
            MaterialTheme {
                CanonicalLibraryBatchActionBar(
                    selectedEntityCount = 2,
                    summary = "5 faixas · 2 itens",
                    onClear = {},
                    onAddToPlaylist = {},
                    onPlayNext = { queued++ },
                    onAddToQueue = {},
                    onRateSelection = {},
                )
            }
        }
        compose.onNodeWithTag("native-selection-expanded-actions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expandir ações").performClick()
        compose.onNodeWithTag("native-selection-expanded-actions").assertIsDisplayed()
        compose.onNodeWithText("Tocar a seleção a seguir").performClick()
        compose.runOnIdle { assertEquals(1, queued) }
        compose.onNodeWithContentDescription("Recolher ações").performClick()
        compose.onNodeWithTag("native-selection-expanded-actions").assertDoesNotExist()
        compose.onNodeWithTag("native-selection-held-card").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expandir ações").performClick()
        compose.onNodeWithTag("native-selection-expanded-actions").assertIsDisplayed()
    }
}
