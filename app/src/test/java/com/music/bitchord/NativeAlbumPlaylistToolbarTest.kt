package com.music.bitchord

import android.app.Application
import androidx.compose.material3.MaterialTheme
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

/** The album's PlaylistAdd icon is an actual clickable native toolbar action. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeAlbumPlaylistToolbarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeAlbumToolbarPlaylistIconOpensTheCallerAction() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                NativeAlbumPlaylistToolbarAction { opens++ }
            }
        }
        compose.onNodeWithContentDescription("Adicionar álbum à playlist")
            .assertIsDisplayed().performClick()
        assertEquals(1, opens)
    }
}
