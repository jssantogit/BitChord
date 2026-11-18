package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The album stays pinnable without fabricating playable media assets. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeAlbumHeroActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun metadataOnlyAlbumRetainsPinWithoutPlayOrShuffle() {
        var pins = 0
        var play = 0
        compose.setContent {
            MaterialTheme {
                NativeAlbumHeroActions(
                    canPlay = false, isPinned = false,
                    onTogglePin = { pins++ },
                    onPlay = { play++ }, onShuffle = { play++ },
                )
            }
        }
        compose.onNodeWithTag("album-hero-pin-only").assertIsDisplayed()
        compose.onNodeWithTag("album-hero-playback-actions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fixar álbum na Biblioteca").performClick()
        compose.runOnIdle { assertEquals(1, pins); assertEquals(0, play) }
    }

    @Test fun playableAlbumKeepsNativeRowWithItsPin() {
        var pins = 0
        compose.setContent {
            MaterialTheme {
                NativeAlbumHeroActions(
                    canPlay = true, isPinned = true,
                    onTogglePin = { pins++ },
                    onPlay = {}, onShuffle = {},
                )
            }
        }
        compose.onNodeWithTag("album-hero-playback-actions").assertIsDisplayed()
        compose.onNodeWithTag("album-hero-pin-only").assertDoesNotExist()
        compose.onNodeWithContentDescription("Desafixar álbum da Biblioteca").performClick()
        compose.runOnIdle { assertEquals(1, pins) }
    }

    @Test fun absentPinCapabilityDoesNotRenderFakeControl() {
        compose.setContent {
            MaterialTheme {
                NativeAlbumHeroActions(
                    canPlay = false, isPinned = false,
                    onTogglePin = null, onPlay = {}, onShuffle = {},
                )
            }
        }
        compose.onNodeWithTag("album-hero-pin-only").assertDoesNotExist()
        compose.onNodeWithTag("album-hero-playback-actions").assertDoesNotExist()
    }
}
