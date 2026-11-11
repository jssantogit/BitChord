package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.CompactRatingGeometry
import com.music.bitchord.ui.components.CompactRatingIndicator
import com.music.bitchord.ui.components.CompactRatingStyle
import com.music.bitchord.ui.components.LocalCompactRatingStyle
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

    @Test fun allRatingsAndBothStylesWorkAtFourCompactSizesForBothGeometries() {
        // Rendering remains one scalar glyph. The artwork's numerical legibility
        // at 24dp/28dp still requires a real-device visual smoke test.
        for (style in CompactRatingStyle.entries) {
            for (geometry in CompactRatingGeometry.entries) {
                for (value in listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0)) {
                    compose.setContent {
                        MaterialTheme {
                            CompositionLocalProvider(LocalCompactRatingStyle provides style) {
                                CompactRatingIndicator(value, geometry, size = 28.dp)
                            }
                        }
                    }
                    compose.onNodeWithTag("compact-rating-indicator").assertExists()
                    compose.onNodeWithContentDescription(
                        "Avaliação: " + value.toString().removeSuffix(".0").replace('.', ',') + " de 5 estrelas",
                    ).assertExists()
                }
            }
        }
    }

    @Test fun styleChangeTakesEffectWithoutLeavingTheComposition() {
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

    @Test fun missingRatingIsInvisibleInListsButNeutralInPlayerActions() {
        compose.setContent {
            MaterialTheme {
                CompactRatingIndicator(null, CompactRatingGeometry.STANDALONE)
            }
        }
        compose.onNodeWithTag("compact-rating-indicator").assertDoesNotExist()
        compose.setContent {
            MaterialTheme {
                CompactRatingIndicator(
                    null, CompactRatingGeometry.CIRCULAR, showUnrated = true, size = 40.dp,
                )
            }
        }
        compose.onNodeWithContentDescription("Sem avaliação").assertExists()
        compose.onNodeWithTag("compact-rating-indicator").assertWidthIsEqualTo(40.dp)
    }
}
