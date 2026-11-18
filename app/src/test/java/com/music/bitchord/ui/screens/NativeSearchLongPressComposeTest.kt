package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Search rows delegate their held actions to the source-verified Haze host.
 * A second popup hidden behind the row would recreate a solid parallel menu. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeSearchLongPressComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPressOnSearchHitCallsHostOnceWithoutAnOpaqueRowPopup() {
        var held = 0
        compose.setContent {
            MaterialTheme {
                NativeSearchResultRow(
                    row = NativeSearchRowUi(
                        title = "Exemplo", subtitle = "Artista",
                        artworkUri = null, artistPortrait = false,
                    ),
                    onClick = {},
                    onLongPress = { held++ },
                )
            }
        }
        compose.onNodeWithTag("native-search-result-row")
            .performTouchInput { longClick() }
        compose.onNodeWithTag("native-compact-overflow").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, held) }
    }

    @Test fun metadataOnlyHitCanOfferHoldWithoutClaimingPlayback() {
        var held = 0
        compose.setContent {
            MaterialTheme {
                NativeSearchResultRow(
                    row = NativeSearchRowUi(
                        title = "Gravação", subtitle = "MusicBrainz",
                        artworkUri = null, artistPortrait = false,
                    ),
                    onClick = null,
                    onLongPress = { held++ },
                )
            }
        }
        compose.onNodeWithTag("native-search-result-row")
            .performTouchInput { longClick() }
        compose.onNodeWithTag("native-compact-overflow").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, held) }
    }
}
