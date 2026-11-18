package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** One inline action language shared by Library, ratings, playlists and party controls. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeInlineTextActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recentAdditionsSeeAllKeepsAccessibleTargetAndCallback() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                NativeInlineTextAction(label = "Ver todos", onClick = { calls++ })
            }
        }
        compose.onNodeWithTag("native-inline-text-action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(44.dp)
        compose.onNodeWithText("Ver todos").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun disabledRatingEditCannotInvokeCallback() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                NativeInlineTextAction(
                    label = "Editar",
                    icon = PlaylistEditorIcons.Ratings,
                    enabled = false,
                    onClick = { calls++ },
                )
            }
        }
        compose.onNodeWithTag("native-inline-text-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun downloadCancelAndClearTextActionsKeepSeparateCallbacks() {
        var cancelled = 0
        var cleared = 0
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Row {
                    NativeInlineTextAction(
                        label = "Cancelar todos",
                        destructive = true,
                        onClick = { cancelled++ },
                    )
                    NativeInlineTextAction(
                        label = "Limpar",
                        subdued = true,
                        onClick = { cleared++ },
                    )
                }
            }
        }
        compose.onNodeWithText("Cancelar todos").performClick()
        compose.onNodeWithText("Limpar").performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals(1, cleared)
        }
    }

    @Test fun destructivePartyActionCanRetainAnIndependentTag() {
        var removals = 0
        compose.setContent {
            MaterialTheme {
                NativeInlineTextAction(
                    label = "Remover",
                    destructive = true,
                    tag = "party-member-remove-action",
                    onClick = { removals++ },
                )
            }
        }
        compose.onNodeWithTag("party-member-remove-action").assertHeightIsAtLeast(44.dp)
        compose.onNodeWithText("Remover").performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }
}
