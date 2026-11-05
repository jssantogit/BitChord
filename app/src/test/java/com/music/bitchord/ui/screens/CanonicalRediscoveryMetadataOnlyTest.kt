package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
class CanonicalRediscoveryMetadataOnlyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun publicMusicBrainzRecordingHasReadOnlyDetailWithoutFeedbackOrAudio() {
        var ratingCalls = 0
        val state = CanonicalRediscoveryRecordingState.MetadataOnly(
            recordingMbid = "13dd61c7-ce73-4e97-9f0c-9f0e53144411",
            title = "Magnetic",
            artistDisplay = "ILLIT",
        )
        compose.setContent {
            MaterialTheme {
                CanonicalRediscoveryRecordingContent(
                    state = state,
                    onBack = {},
                    feedback = { ratingCalls++; Text("Editar avaliação") },
                )
            }
        }
        compose.onNodeWithText("Magnetic").assertExists()
        compose.onNodeWithText("ILLIT").assertExists()
        compose.onNodeWithText("Editar avaliação").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, ratingCalls) }
    }
}
