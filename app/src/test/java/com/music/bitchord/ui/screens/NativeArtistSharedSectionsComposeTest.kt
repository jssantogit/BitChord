package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import com.music.bitchord.ui.theme.themeArtworkPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeArtistSharedSectionsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun canonicalTrackActionsSurviveNativeArtistCarouselReuse() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                NativeArtistTracksCarousel(listOf("recording-a"), itemKey = { it }) { id ->
                    Row {
                        listOf("rating", "menu", "selection").forEach { action ->
                            IconButton(onClick = { calls += "$action:$id" },
                                modifier = Modifier.testTag(action)) { Text("·") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("rating").performClick()
        compose.onNodeWithTag("menu").performClick()
        compose.onNodeWithTag("selection").performClick()
        compose.runOnIdle {
            assertEquals(listOf("rating:recording-a", "menu:recording-a",
                "selection:recording-a"), calls)
        }
    }

    @Test fun nativeReleaseCarouselPreservesNestedAlbumMenuAction() {
        var chosen: String? = null
        compose.setContent {
            MaterialTheme {
                NativeArtistReleaseCarousel(listOf("release-a"), itemKey = { it }) { id ->
                    IconButton(onClick = { chosen = id }, modifier = Modifier.testTag("release-menu")) {
                        Text("⋮")
                    }
                }
            }
        }
        compose.onNodeWithTag("release-menu").performClick()
        compose.runOnIdle { assertEquals("release-a", chosen) }
    }
    @Test fun sharedArtistTrackShelfKeepsHeadingAndTypedRowAction() {
        var plays = 0
        compose.setContent {
            MaterialTheme {
                NativeArtistTracksShelf(
                    title = "Faixas",
                    tracks = listOf("recording-a"),
                    palette = themeArtworkPalette(),
                    itemKey = { it },
                ) { recording ->
                    IconButton(
                        onClick = { if (recording == "recording-a") plays++ },
                        modifier = Modifier.testTag("typed-track-action"),
                    ) { Text("Play") }
                }
            }
        }
        compose.onNodeWithText("Faixas").assertExists()
        compose.onNodeWithTag("typed-track-action").performClick()
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun sharedArtistReleaseShelfKeepsHeadingAndMenuAction() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                NativeArtistReleaseShelf(
                    title = "Singles e EPs",
                    releases = listOf("release-a"),
                    palette = themeArtworkPalette(),
                    itemKey = { it },
                ) { release ->
                    IconButton(
                        onClick = { if (release == "release-a") opens++ },
                        modifier = Modifier.testTag("typed-release-menu"),
                    ) { Text("Menu") }
                }
            }
        }
        compose.onNodeWithText("Singles e EPs").assertExists()
        compose.onNodeWithTag("typed-release-menu").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun nativeAlbumCardKeepsMoreActionAndMainNavigationIndependent() {
        var opened = 0
        var options = 0
        compose.setContent {
            MaterialTheme {
                val palette = themeArtworkPalette()
                NativeArtistReleaseCard(
                    title = "Album", subtitle = "aespa",
                    palette = palette,
                    modifier = Modifier.testTag("native-release-card"),
                    onClick = { opened++ },
                    onMore = { options++ },
                    artwork = { Box(Modifier.fillMaxSize()) },
                )
            }
        }
        compose.onNodeWithContentDescription("Opções de Album").performClick()
        compose.onNodeWithTag("native-release-card").performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, options)
        }
    }

    @Test fun selectionReplacesTheMoreActionButPreservesSelectionCallback() {
        var toggled = 0
        compose.setContent {
            MaterialTheme {
                val palette = themeArtworkPalette()
                NativeArtistReleaseCard(
                    title = "Album", subtitle = "artist", palette = palette,
                    onClick = {},
                    onMore = { error("Menu must be replaced during selection") },
                    selectionMode = true,
                    onToggleSelection = { toggled++ },
                    artwork = { Box(Modifier.fillMaxSize()) },
                )
            }
        }
        compose.onNodeWithContentDescription("Opções de Album").assertDoesNotExist()
        compose.onNodeWithContentDescription("Selecionar Album").performClick()
        compose.runOnIdle { assertEquals(1, toggled) }
    }

    @Test fun sharedNativeArtistFrameShowsContentAndKeepsClicksAlive() {
        var ratingOpens = 0
        compose.setContent {
            MaterialTheme {
                val palette = themeArtworkPalette()
                NativeArtistDetailFrame(
                    palette = palette,
                    artHeight = 220.dp,
                    listState = rememberLazyListState(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    item(key = "artist-header") { Text("Mesmo artista") }
                    item(key = "artist-rating") {
                        IconButton(
                            onClick = { ratingOpens++ },
                            modifier = Modifier.testTag("artist-rating-in-frame"),
                        ) { Text("★") }
                    }
                }
            }
        }
        compose.onNodeWithText("Mesmo artista").assertExists()
        compose.onNodeWithTag("artist-rating-in-frame").performClick()
        compose.runOnIdle { assertEquals(1, ratingOpens) }
    }

    @Test fun artistHeaderKeepsReadableNameWhenArtworkProvidersHaveNoLogo() {
        compose.setContent {
            MaterialTheme {
                ArtistHeader(
                    artistName = "aespa",
                    palette = themeArtworkPalette(),
                    artHeight = 210.dp,
                    appleArt = null,
                    signature = null,
                )
            }
        }
        compose.onNodeWithText("aespa").assertExists()
    }

}
