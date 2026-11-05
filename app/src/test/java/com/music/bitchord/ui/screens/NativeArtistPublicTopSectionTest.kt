package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.ui.theme.themeArtworkPalette
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
class NativeArtistPublicTopSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyExactArtistRecordingOpensMetadataThroughNativeTrackShelf() {
        val artist = "b7ffd2af-418f-4be2-bdd1-22f8b48613da"
        val correct = CanonicalCatalogSearchRow(
            musicBrainzId = "13dd61c7-ce73-4e97-9f0c-9f0e53144411",
            kind = CanonicalSearchKind.RECORDING,
            title = "Canonical recording", subtitle = "Artist",
            artistScopeMbid = artist,
        )
        val unrelated = correct.copy(
            musicBrainzId = "22ad712e-ce73-9f0c-9f0e53144411",
            title = "Other identity",
            artistScopeMbid = "b7ffd2af-418f-4be2-bdd1-22f8b48613db",
        )
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                NativeArtistPublicTopSection(
                    // Incoming browse MBIDs may use uppercase hex. Identity
                    // normalization must not make the verified shelf disappear.
                    artistMbid = artist.uppercase(),
                    rows = listOf(correct, unrelated),
                    palette = themeArtworkPalette(),
                    onRecordingOpen = { opened += it.musicBrainzId },
                )
            }
        }
        compose.onNodeWithText("Canonical recording").assertExists()
        compose.onNodeWithText("Other identity").assertDoesNotExist()
        compose.onNodeWithText("Canonical recording").performClick()
        compose.runOnIdle { assertEquals(listOf(correct.musicBrainzId), opened) }
    }
}
