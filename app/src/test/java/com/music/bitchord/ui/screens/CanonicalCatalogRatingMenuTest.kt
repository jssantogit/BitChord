package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
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

    @Test fun catalogReleaseThreeDotMenuUsesTheSharedNativePopup() {
        compose.setContent {
            MaterialTheme {
                CanonicalCatalogReleaseOverflowMenu(
                    browseId = "catalog:RELEASE:66dd7663-76a4-4e31-93ae-730ccd49d7d8",
                    title = "Test Album", subtitle = "Test Artist", cover = null,
                    expanded = true, onDismiss = {},
                    hazeState = remember { HazeState() }, onSettings = {},
                )
            }
        }
        compose.onNodeWithTag("native-frosted-overflow").assertExists()
        compose.onNodeWithText("Configurações").assertExists()
    }

    @Test fun heldMetadataRecordingOffersRatingWithoutAnySongEllipsis() {
        var edits = 0
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    CanonicalMetadataRecordingHeldActions(
                        recordingId = "66dd7663-76a4-4e31-93ae-730ccd49d7d8",
                        title = "Circles", artist = "ASTRO",
                        onDismiss = {}, onRate = { edits++ },
                        onAddToPlaylist = { _, _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Song options").assertDoesNotExist()
        compose.onNodeWithText("Avaliar faixa").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }
}
