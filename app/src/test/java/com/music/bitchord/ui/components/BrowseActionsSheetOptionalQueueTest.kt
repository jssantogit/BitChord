package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.R
import com.music.bitchord.data.model.BrowseType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Canonical pins may reuse the native menu without advertising fake queue operations. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class BrowseActionsSheetOptionalQueueTest {
    @get:Rule val compose = createComposeRule()

    private val pinnedAlbum = BrowseTarget(
        browseId = null,
        title = "Pinned release",
        subtitle = "Artist",
        type = BrowseType.ALBUM,
    )

    @Test fun noQueueHandlersMeansNoNonfunctionalQueueRows() {
        compose.setContent {
            MaterialTheme {
                BrowseActionsSheet(
                    target = pinnedAlbum,
                    isPinned = true,
                    onTogglePin = {},
                    onAddToPlaylist = {},
                )
            }
        }
        val app = RuntimeEnvironment.getApplication()
        compose.onNodeWithText(app.getString(R.string.play_next)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.add_to_queue)).assertDoesNotExist()
        compose.onNodeWithText("Adicionar à playlist").assertExists()
    }

    @Test fun nativeQueueRowsRemainFunctionalWhenHandlersAreProvided() {
        var playNext = 0
        var addToQueue = 0
        compose.setContent {
            MaterialTheme {
                BrowseActionsSheet(
                    target = pinnedAlbum,
                    onPlayNext = { playNext++ },
                    onAddToQueue = { addToQueue++ },
                )
            }
        }
        val app = RuntimeEnvironment.getApplication()
        compose.onNodeWithText(app.getString(R.string.play_next)).performClick()
        compose.onNodeWithText(app.getString(R.string.add_to_queue)).performClick()
        compose.runOnIdle {
            assertEquals(1, playNext)
            assertEquals(1, addToQueue)
        }
    }
}
