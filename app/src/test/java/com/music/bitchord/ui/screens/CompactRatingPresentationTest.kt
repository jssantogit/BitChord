package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.CompactRatingGeometry
import com.music.bitchord.ui.components.CompactRatingIndicator
import com.music.bitchord.ui.components.CompactRatingStyle
import com.music.bitchord.ui.components.CanonicalRatingStars
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.ui.components.LocalCompactRatingStyle
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
class CompactRatingPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allNineScoresAndFourSizesAreAccessibleInBothStylesAndGeometries() {
        val style = mutableStateOf(CompactRatingStyle.COLOR)
        val geometry = mutableStateOf(CompactRatingGeometry.STANDALONE)
        val score = mutableStateOf(1.0)
        val size = mutableStateOf(24.dp)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCompactRatingStyle provides style.value) {
                    CompactRatingIndicator(score.value, geometry.value, size = size.value)
                }
            }
        }
        for (s in CompactRatingStyle.entries) for (g in CompactRatingGeometry.entries) {
            for (v in listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0)) {
                for (dp in listOf(24.dp, 28.dp, 34.dp, 40.dp)) {
                    compose.runOnIdle {
                        style.value = s
                        geometry.value = g
                        score.value = v
                        size.value = dp
                    }
                    compose.onNodeWithTag("compact-rating-indicator").assertWidthIsEqualTo(dp)
                    compose.onNodeWithContentDescription(
                        "Avaliação: " + v.toString().removeSuffix(".0").replace('.', ',') + " de 5 estrelas",
                    ).assertExists()
                }
            }
        }
    }

    @Test fun styleChangeTakesEffectWithoutRecreatingTheComposition() {
        val style = mutableStateOf(CompactRatingStyle.COLOR)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCompactRatingStyle provides style.value) {
                    CompactRatingIndicator(4.5, CompactRatingGeometry.CIRCULAR, size = 34.dp)
                }
            }
        }
        compose.onNodeWithTag("compact-rating-color", useUnmergedTree = true).assertExists()
        compose.runOnIdle { style.value = CompactRatingStyle.NUMERIC }
        compose.onNodeWithTag("compact-rating-numeric", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("compact-rating-color", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun customArtistRatingIconPreservesActionLabelAndExactAccessibleScore() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                CircleIconButton(
                    icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.Ratings,
                    contentDescription = "Avaliar artista · Avaliação: 4,5 de 5 estrelas",
                    onClick = { opened++ },
                    customIcon = {
                        CompactRatingIndicator(
                            value = 4.5,
                            geometry = CompactRatingGeometry.STANDALONE,
                            size = 23.dp,
                        )
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("Avaliar artista", substring = true)
            .performClick()
        compose.onNodeWithContentDescription("4,5 de 5 estrelas", substring = true)
            .assertExists()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun malformedLocalOrRemoteScoresNeverCreateGhostRatingRows() {
        compose.setContent {
            MaterialTheme {
                Column {
                    CanonicalRatingStars(RatingDisplay(localValue = 2.25))
                    CanonicalRatingStars(RatingDisplay(remoteValue = 0))
                    CanonicalRatingStars(RatingDisplay(localValue = Double.NaN))
                }
            }
        }
        compose.onNodeWithTag("compact-rating-indicator").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sem avaliação", substring = true)
            .assertDoesNotExist()
    }

    @Test fun unverifiedSongHasNoListRatingButAnUnratedPlayerControl() {
        val show = mutableStateOf(false)
        val geometry = mutableStateOf(CompactRatingGeometry.STANDALONE)
        compose.setContent {
            MaterialTheme {
                CompactRatingIndicator(
                    null, geometry.value, showUnrated = show.value, size = 40.dp,
                )
            }
        }
        compose.onNodeWithTag("compact-rating-indicator").assertDoesNotExist()
        compose.runOnIdle {
            show.value = true
            geometry.value = CompactRatingGeometry.CIRCULAR
        }
        compose.onNodeWithContentDescription("Sem avaliação").assertExists()
        compose.onNodeWithTag("compact-rating-indicator").assertWidthIsEqualTo(40.dp)
    }
}
