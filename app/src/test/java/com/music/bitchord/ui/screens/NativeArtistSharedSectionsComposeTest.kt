package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
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
}
