package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** + in a canonical album detail is for future Library pins, never playlist writes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeAlbumPinReservationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun canonicalAlbumHeroPlusIsNonInteractivePinPlaceholder() {
        compose.setContent {
            MaterialTheme {
                NativeReleaseActionRow(
                    onPlay = {},
                    onShuffle = {},
                    isLocal = false,
                    reservePinAction = true,
                    // Even if a legacy caller incorrectly supplies a callback,
                    // the future pin placeholder cannot mutate any Library state.
                    onToggleLibrary = { throw AssertionError("Plus must not be active") },
                )
            }
        }
        compose.onNodeWithContentDescription("Fixar álbum na Biblioteca (em breve)")
            .assertIsNotEnabled()
    }
    @Test fun nativeAlbumHeroPlusPinsAndBecomesCheckToUnpin() {
        var taps = 0
        compose.setContent {
            var pinned by androidx.compose.runtime.remember { mutableStateOf(false) }
            MaterialTheme {
                NativeReleaseActionRow(
                    onPlay = {},
                    onShuffle = {},
                    isLocal = false,
                    reservePinAction = true,
                    isPinnedInLibrary = pinned,
                    onTogglePin = {
                        taps++
                        pinned = !pinned
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("Fixar álbum na Biblioteca")
            .assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Desafixar álbum da Biblioteca")
            .assertIsDisplayed().performClick()
        assertEquals(2, taps)
    }

}
