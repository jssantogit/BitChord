package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A canonical provider row can receive its first LOCAL rating through ⋮
 * even when neither Navidrome nor BitChord has supplied a numeric score.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalCatalogRatingMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun threeDotMenuOpensFirstNativeRatingEditorWithoutStarPlaceholder() {
        var edits = 0
        compose.setContent {
            MaterialTheme {
                CanonicalCatalogRatingMenu(onEdit = { edits++ })
            }
        }
        compose.onNodeWithText("Edit rating").assertDoesNotExist()
        compose.onNodeWithContentDescription("Song options").performClick()
        compose.onNodeWithText("Edit rating").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }
}
