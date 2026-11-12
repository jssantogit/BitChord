package com.music.bitchord.ui.screens

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

/** Ratings entry/edit controls retain their callbacks without Material3 text buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MyRatingsNativeActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editActionPreservesRatingGlyphAndOpensSelectedRecording() {
        var edits = 0
        compose.setContent {
            MaterialTheme {
                MyRatingsInlineAction(
                    label = "Editar avaliação",
                    icon = PlaylistEditorIcons.Ratings,
                    onClick = { edits++ },
                )
            }
        }
        compose.onNodeWithTag("my-ratings-inline-action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(44.dp)
        compose.onNodeWithText("Editar avaliação").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test fun disabledRatingsActionCannotNavigate() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                MyRatingsInlineAction(
                    label = "Fechar",
                    subdued = true,
                    enabled = false,
                    onClick = { calls++ },
                )
            }
        }
        compose.onNodeWithTag("my-ratings-inline-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
