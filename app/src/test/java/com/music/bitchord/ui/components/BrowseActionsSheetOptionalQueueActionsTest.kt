package com.music.bitchord.ui.components

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.music.bitchord.R
import com.music.bitchord.data.model.BrowseType
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** A canonical pinned shelf cannot expose queue buttons without real queue callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class BrowseActionsSheetOptionalQueueActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pinnedCanonicalEntityOmitsUnsupportedQueueActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var pinTaps = 0
        var playlistTaps = 0
        compose.setContent {
            MaterialTheme {
                BrowseActionsSheet(
                    target = BrowseTarget(
                        browseId = null,
                        title = "Album",
                        subtitle = "Artist",
                        type = BrowseType.ALBUM,
                    ),
                    isPinned = true,
                    onTogglePin = { pinTaps++ },
                    onAddToPlaylist = { playlistTaps++ },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.play_next)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.add_to_queue)).assertDoesNotExist()
        compose.onNodeWithText("Adicionar à playlist").assertExists().performClick()
        compose.onNodeWithText(context.getString(R.string.unpin)).assertExists().performClick()
        assertEquals(1, playlistTaps)
        assertEquals(1, pinTaps)
    }

    @Test fun albumActionsReuseThePlayerHeaderGeometryButKeepTheirOwnCommands() {
        var playlistTaps = 0
        var ratingTaps = 0
        compose.setContent {
            MaterialTheme {
                BrowseActionsSheet(
                    target = BrowseTarget(
                        browseId = "verified-album",
                        title = "ARIRANG",
                        subtitle = "BTS",
                        type = BrowseType.ALBUM,
                    ),
                    onAddToPlaylist = { playlistTaps++ },
                    onEditRating = { ratingTaps++ },
                )
            }
        }
        compose.onNodeWithTag("native-solid-browse-header")
            .assertHeightIsEqualTo(112.dp)
        compose.onNodeWithText("Adicionar à playlist").performClick()
        compose.onNodeWithText("Editar avaliação").performClick()
        assertEquals(1, playlistTaps)
        assertEquals(1, ratingTaps)
    }

    @Test fun realQueueCallbacksKeepBothExistingActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var nextTaps = 0
        var queueTaps = 0
        compose.setContent {
            MaterialTheme {
                BrowseActionsSheet(
                    target = BrowseTarget(
                        browseId = "verified-album",
                        title = "Album",
                        subtitle = "Artist",
                        type = BrowseType.ALBUM,
                    ),
                    onPlayNext = { nextTaps++ },
                    onAddToQueue = { queueTaps++ },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.play_next)).assertExists().performClick()
        compose.onNodeWithText(context.getString(R.string.add_to_queue)).assertExists().performClick()
        assertEquals(1, nextTaps)
        assertEquals(1, queueTaps)
    }
}
