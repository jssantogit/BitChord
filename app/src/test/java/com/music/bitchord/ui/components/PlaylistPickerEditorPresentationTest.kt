package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaylistPrivacy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Visual redesign must preserve the account playlist creation contract. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistPickerEditorPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun quickPlaylistEditorKeepsExplicitSaveAndPrivateDefault() {
        val created = mutableListOf<Pair<String, PlaylistPrivacy>>()
        compose.setContent {
            MaterialTheme {
                PlaylistPickerSheet(
                    playlists = emptyList(),
                    loading = false,
                    onAdd = {},
                    onCreate = { name, privacy -> created += name to privacy },
                    startCreating = true,
                )
            }
        }
        val title = RuntimeEnvironment.getApplication().getString(R.string.create_playlist)
        compose.onNodeWithText(title).assertIsNotEnabled()
        compose.onNodeWithTag("playlist-editor-create-name").performTextInput("Night mix")
        compose.onNodeWithText(title).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("Night mix" to PlaylistPrivacy.PRIVATE), created) }
    }
}
