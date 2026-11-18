package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.ArtworkPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Release title and credit never compete for width with an inline rating badge. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeReleaseRatingLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val palette = ArtworkPalette(
        background = Color.Black, wash = Color.Black,
        elevated = Color.DarkGray, accent = Color.White,
        onBackground = Color.White, onBackgroundVariant = Color.LightGray,
        divider = Color.Gray,
    )

    @Test fun ratedAlbumHeaderShowsIdentityButNoRatingBadgeOrInlineEditor() {
        compose.setContent {
            MaterialTheme {
                NativeReleaseHeaderLayout(
                    title = "ARIRANG", credit = "BTS", meta = "",
                    palette = palette, rating = 4.5,
                    onRate = {}, sleeveSize = 96.dp,
                    topInset = 0.dp, artwork = {}, actions = {},
                )
            }
        }
        compose.onNodeWithTag("native-release-title").assertExists()
        compose.onNodeWithTag("native-release-credit").assertExists()
        compose.onNodeWithTag("native-album-rating").assertDoesNotExist()
    }

    @Test fun ratedPlaylistWithoutCreditDoesNotCreateAThirdRow() {
        compose.setContent {
            MaterialTheme {
                NativeReleaseHeaderLayout(
                    title = "Minha Playlist", credit = "", meta = "Playlist",
                    palette = palette, rating = 3.5,
                    onRate = {}, sleeveSize = 96.dp,
                    topInset = 0.dp, artwork = {}, actions = {},
                )
            }
        }
        compose.onNodeWithTag("native-release-title").assertExists()
        compose.onNodeWithTag("native-release-credit").assertDoesNotExist()
        compose.onNodeWithTag("native-album-rating").assertDoesNotExist()
    }

    @Test fun unknownRatingDoesNotInventAnIndicator() {
        compose.setContent {
            MaterialTheme {
                NativeReleaseHeaderLayout(
                    title = "Sem nota", credit = "Artista", meta = "",
                    palette = palette, rating = null, onRate = null,
                    sleeveSize = 96.dp, topInset = 0.dp,
                    artwork = {}, actions = {},
                )
            }
        }
        compose.onNodeWithTag("native-album-rating").assertDoesNotExist()
    }
}
