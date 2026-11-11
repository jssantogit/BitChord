package com.music.bitchord

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Album toolbar replaces PlaylistAdd with the actual rating action. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeAlbumPlaylistToolbarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeReleaseRatingButtonUsesExistingCanonicalEditorCallback() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                NativeCollectionRatingToolbarAction(
                    rating = 4.5, subject = "álbum", onClick = { taps++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Avaliar álbum", substring = true)
            .assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("4,5 de 5 estrelas", substring = true)
            .assertExists()
        compose.onNodeWithContentDescription("Adicionar álbum à playlist")
            .assertDoesNotExist()
        assertEquals(1, taps)
    }

    @Test fun unratedPlaylistIsActionableWithoutFabricatingAScore() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                NativeCollectionRatingToolbarAction(
                    rating = null, subject = "playlist", onClick = { taps++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Avaliar playlist", substring = true)
            .performClick()
        compose.onNodeWithContentDescription("5 de 5 estrelas", substring = true)
            .assertDoesNotExist()
        assertEquals(1, taps)
    }
}
