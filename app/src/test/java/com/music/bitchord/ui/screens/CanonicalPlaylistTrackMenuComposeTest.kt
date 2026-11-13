package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
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

/** The ⋯ route must remain native, and Opening Album must call the verified link. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalPlaylistTrackMenuComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun moreActionsExposesVerifiedOpenAlbumWithoutGuessingIt() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                CanonicalPlaylistTrackRow(
                    track = V2DisplayRow("row-1", "A song", "Artist"),
                    onPlay = {},
                    onQueue = { _, _ -> },
                    onOpenAlbum = { opened++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Ações da música").performClick()
        compose.onNodeWithText("Abrir álbum").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun noCanonicalAlbumEvidenceMeansNoOpenAlbumAction() {
        compose.setContent {
            MaterialTheme {
                CanonicalPlaylistTrackRow(
                    track = V2DisplayRow("row-2", "Another song", "Artist"),
                    onPlay = {},
                    onQueue = { _, _ -> },
                )
            }
        }
        compose.onNodeWithContentDescription("Ações da música").performClick()
        compose.onNodeWithText("Abrir álbum").assertDoesNotExist()
    }
}
