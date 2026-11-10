package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaylistPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import com.music.bitchord.ui.icons.PlaylistEditorIcons
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

    @Test fun contextualRatingAndPlaylistDeleteReuseOriginalEditorVectors() {
        assertSame(PlaylistEditorIcons.Ratings, browseRatingActionIcon())
        assertSame(PlaylistEditorIcons.Delete, browsePlaylistDeleteIcon())
    }

    @Test fun newPlaylistDoesNotDiscardAnEditedNameWithoutConfirmation() {
        var created = 0
        var dirty = false
        compose.setContent {
            MaterialTheme {
                PlaylistPickerSheet(
                    playlists = emptyList(),
                    loading = false,
                    onAdd = {},
                    onCreate = { _, _, done -> created++; done(true) },
                    onDraftChanged = { dirty = it },
                )
            }
        }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.new_playlist)).performClick()
        compose.onNodeWithTag("playlist-editor-create-name").performTextInput("Night mix")
        compose.runOnIdle { assertEquals(true, dirty) }
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.back)).performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithTag("playlist-editor-create-name").assertExists()
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.back)).performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle {
            assertEquals(false, dirty)
            assertEquals(0, created)
        }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.new_playlist)).assertExists()
    }

    @Test fun failedPlaylistSaveKeepsFormAndAllowsRetry() {
        val requests = mutableListOf<Pair<String, PlaylistPrivacy>>()
        var success = false
        compose.setContent {
            MaterialTheme {
                PlaylistPickerSheet(
                    playlists = emptyList(),
                    loading = false,
                    onAdd = {},
                    onCreate = { name, privacy, done ->
                        requests += name to privacy
                        done(success)
                    },
                    startCreating = true,
                )
            }
        }
        val save = RuntimeEnvironment.getApplication().getString(R.string.create_playlist)
        compose.onNodeWithTag("playlist-editor-create-name").performTextInput("  Night mix  ")
        compose.onNodeWithText(save).performClick()
        compose.onNodeWithText("Couldn't save the playlist. Try again.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("Night mix" to PlaylistPrivacy.PRIVATE), requests) }
        compose.runOnIdle { success = true }
        compose.onNodeWithText(save).performClick()
        compose.runOnIdle { assertEquals(2, requests.size) }
    }

    @Test fun quickPlaylistEditorKeepsExplicitSaveAndPrivateDefault() {
        val created = mutableListOf<Pair<String, PlaylistPrivacy>>()
        compose.setContent {
            MaterialTheme {
                PlaylistPickerSheet(
                    playlists = emptyList(),
                    loading = false,
                    onAdd = {},
                    onCreate = { name, privacy, done -> created += name to privacy; done(true) },
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
