package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Pinned Library is a cover-only shelf, not duplicated cards with labels and overflow. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PinnedArtworkOnlyComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pinnedTileIsSquareAndOnlyArtworkClickableWithAccessibleName() {
        var opens = 0
        var menus = 0
        compose.setContent {
            BitChordTheme {
                Box(Modifier.size(100.dp)) {
                    PinnedLibraryArtworkTile(
                        title = "kk",
                        onClick = { opens++ },
                        onLongPress = { menus++ },
                    ) { Box(Modifier.size(100.dp)) }
                }
            }
        }
        compose.onNodeWithTag("pinned-library-artwork")
            .assertWidthIsEqualTo(100.dp)
            .assertHeightIsEqualTo(100.dp)
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        compose.onNodeWithText("kk").assertDoesNotExist()
        compose.onNodeWithContentDescription("kk").assertExists()
        compose.onNodeWithTag("pinned-library-artwork").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, menus) }
    }

    @Test fun localPinnedLookupReusesOriginalShelfIdentityRatherThanAddingLocalPrefixTwice() {
        val local = ShelfItem(title = "Cover", browseId = "local:playlist:xyz")
        val indexed = pinnedLocalPlaylistItemsById(listOf(local))
        assertSame(local, indexed["local:playlist:xyz"])
    }
}
