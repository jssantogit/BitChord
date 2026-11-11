package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.ArtworkPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeReleaseRatingLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val palette = ArtworkPalette(
        background = Color.Black,
        wash = Color.Black,
        elevated = Color.DarkGray,
        accent = Color.White,
        onBackground = Color.White,
        onBackgroundVariant = Color.LightGray,
        divider = Color.Gray,
    )

    @Test fun savedAlbumRatingIsInlineWithCreditAndPreservesEditorClick() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                NativeReleaseHeaderLayout(
                    title = "ARIRANG", credit = "BTS", meta = "",
                    palette = palette, rating = 4.5,
                    onRate = { opens++ }, sleeveSize = 96.dp,
                    topInset = 0.dp, artwork = {}, actions = {},
                )
            }
        }
        val credit = compose.onNodeWithTag("native-release-credit").fetchSemanticsNode().boundsInRoot
        val rating = compose.onNodeWithTag("native-album-rating").fetchSemanticsNode().boundsInRoot
        assertTrue("Rating must share the credit's line", credit.top < rating.bottom && rating.top < credit.bottom)
        compose.onNodeWithTag("native-album-rating").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun playlistWithoutCreditKeepsRatingInlineWithTitle() {
        compose.setContent {
            MaterialTheme {
                NativeReleaseHeaderLayout(
                    title = "My Playlist", credit = "", meta = "Playlist",
                    palette = palette, rating = 3.5,
                    onRate = {}, sleeveSize = 96.dp,
                    topInset = 0.dp, artwork = {}, actions = {},
                )
            }
        }
        val title = compose.onNodeWithTag("native-release-title").fetchSemanticsNode().boundsInRoot
        val rating = compose.onNodeWithTag("native-album-rating").fetchSemanticsNode().boundsInRoot
        assertTrue("Rating must share the playlist title's line", title.top < rating.bottom && rating.top < title.bottom)
    }

    @Test fun missingScoreWithoutEditorHasNoRatingIndicator() {
        compose.setContent {
            MaterialTheme {
                NativeReleaseHeaderLayout(
                    title = "Unrated", credit = "Artist", meta = "",
                    palette = palette, rating = null,
                    onRate = null, sleeveSize = 96.dp,
                    topInset = 0.dp, artwork = {}, actions = {},
                )
            }
        }
        compose.onNodeWithTag("native-album-rating").assertDoesNotExist()
    }
}
