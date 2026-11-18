package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.data.model.BrowseType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The shared Library menu must not invent an album until its route is verified. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class BrowseActionsOpenAlbumTest {
    @get:Rule val compose = createComposeRule()

    @Test fun verifiedTrackAlbumCanOpenItsRealDetail() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                BrowseActionsSheet(
                    target = BrowseTarget(null, "Track", "Artist", type = BrowseType.OTHER),
                    presentation = SongActionsPresentation.Menu,
                    onOpenAlbum = { opened++ },
                )
            }
        }
        compose.onNodeWithText("Abrir álbum").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun missingLinkMustNotShowOpenAlbum() {
        compose.setContent {
            MaterialTheme {
                BrowseActionsSheet(
                    target = BrowseTarget(null, "Track", "Artist", type = BrowseType.OTHER),
                    presentation = SongActionsPresentation.Menu,
                )
            }
        }
        compose.onNodeWithText("Abrir álbum").assertDoesNotExist()
    }
}
