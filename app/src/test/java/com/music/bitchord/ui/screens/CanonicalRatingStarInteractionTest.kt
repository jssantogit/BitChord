package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.CanonicalSongRatingStars
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.music.bitchord.ui.components.CanonicalRatingStars
import com.music.bitchord.ui.components.RatingDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** A displayed Navidrome score acts only as the trigger for LOCAL editing. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalRatingStarInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingReadOnlyRemoteStarsInvokesEditorCallbackNotProviderWrite() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                CanonicalRatingStars(
                    rating = RatingDisplay(remoteValue = 5),
                    onClick = { opens++ },
                )
            }
        }
        compose.onNodeWithContentDescription("5/5", substring = true).performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun tappingConflictingScoreStillUsesOneLocalEditorTrigger() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                CanonicalRatingStars(
                    rating = RatingDisplay(localValue = 4.5, remoteValue = 3),
                    onClick = { opens++ },
                )
            }
        }
        compose.onNodeWithContentDescription("4.5/5", substring = true).performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun nowPlayingRemoteOnlyIsReadOnlyUntilFirstNativeScoreFromMenu() {
        val song = Song(videoId = "content://media/song", title = "Test", artist = "Artist", thumbnailUrl = null)
        var opens = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCanonicalRatingDisplay provides CanonicalRatingDisplaySnapshot(
                        bySongKey = mapOf(song.videoId to RatingDisplay(remoteValue = 4)),
                    ),
                ) {
                    CanonicalSongRatingStars(song = song, onClick = { opens++ })
                }
            }
        }
        val stars = compose.onNodeWithContentDescription("4/5", substring = true)
            .fetchSemanticsNode()
        compose.runOnIdle {
            assertNull(stars.config.getOrNull(SemanticsActions.OnClick))
            assertEquals(0, opens)
        }
    }

    @Test fun firstNativeScoreActivatesDirectNowPlayingEditor() {
        val song = Song(videoId = "content://media/song", title = "Test", artist = "Artist", thumbnailUrl = null)
        var opens = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCanonicalRatingDisplay provides CanonicalRatingDisplaySnapshot(
                        bySongKey = mapOf(song.videoId to RatingDisplay(localValue = 3.5)),
                    ),
                ) {
                    CanonicalSongRatingStars(song = song, onClick = { opens++ })
                }
            }
        }
        compose.onNodeWithContentDescription("3.5/5", substring = true).performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
