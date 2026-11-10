package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.R
import com.music.bitchord.ui.theme.BitChordTheme
import com.music.bitchord.ui.screens.CircleIconButton
import com.music.bitchord.ui.screens.ActionPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Global Apple-style chrome contracts. Profile/settings access is never lost,
 * and multiple top-bar actions must have separate circular surfaces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class GlobalAppleTopBarChromeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accountAvatarBecomesThreeDotSettingsMenu() {
        var enteredSettings = false
        compose.setContent {
            BitChordTheme {
                TopBarAccountButton(account = null, onClick = { enteredSettings = true })
            }
        }
        val res = RuntimeEnvironment.getApplication().resources
        compose.onNodeWithContentDescription(res.getString(R.string.more)).performClick()
        compose.onNodeWithText(res.getString(R.string.settings)).performClick()
        compose.runOnIdle { assertTrue(enteredSettings) }
    }

    @Test fun globalTopBarActionsHaveIndependentCircleSurfaces() {
        compose.setContent {
            BitChordTheme {
                ArtworkPageActions(hazeState = null) {
                    IconButton(onClick = {}) { Icon(Icons.Rounded.Search, contentDescription = "Search") }
                    // AnimatedVisibility and other absent toolbar slots must not
                    // leave an empty glass circle behind.
                    Spacer(Modifier.size(0.dp))
                    // A compact non-icon indicator is not a toolbar action.
                    Spacer(Modifier.size(20.dp))
                    IconButton(onClick = {}) { Icon(Icons.Rounded.Sort, contentDescription = "Sort") }
                }
            }
        }
        val circles = compose.onAllNodesWithTag("apple-toolbar-action-circle", useUnmergedTree = true)
        circles.assertCountEquals(2)
        circles[0].assertWidthIsEqualTo(40.dp)
        circles[1].assertWidthIsEqualTo(40.dp)
        compose.onNodeWithContentDescription("Search").assertExists()
        compose.onNodeWithContentDescription("Sort").assertExists()
    }
    @Test fun threeIconToolbarUsesFullTapTargetsAtNarrowAvailableWidth() {
        compose.setContent {
            BitChordTheme {
                ArtworkPageActions(
                    hazeState = null,
                    modifier = Modifier.width(152.dp).testTag("narrow-action-toolbar"),
                ) {
                    listOf("One", "Two", "Three").forEach { label ->
                        IconButton(onClick = {}) {
                            Icon(Icons.Rounded.Sort, contentDescription = label)
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("narrow-action-toolbar", useUnmergedTree = true)
            .assertWidthIsEqualTo(152.dp)
        val circles = compose.onAllNodesWithTag("apple-toolbar-action-circle", useUnmergedTree = true)
        circles.assertCountEquals(3)
        (0..2).forEach { circles[it].assertWidthIsEqualTo(40.dp) }
    }


    @Test fun libraryCategoryBackAndActionsDrawEqualFortyDpCircles() {
        var backs = 0
        compose.setContent {
            BitChordTheme {
                Row {
                    ArtworkPageBackButton(
                        onClick = { backs++ },
                        hazeState = null,
                        isCategory = true,
                    )
                    FloatingLibraryTopBarCircle(hazeState = null) {
                        IconButton(onClick = {}) {
                            Icon(Icons.Rounded.Sort, contentDescription = "Library sort")
                        }
                    }
                }
            }
        }
        val circles = compose.onAllNodesWithTag("apple-library-floating-circle", useUnmergedTree = true)
        circles.assertCountEquals(2)
        circles[0].assertWidthIsEqualTo(AppleFloatingControlMetrics.libraryDiameter)
        circles[1].assertWidthIsEqualTo(AppleFloatingControlMetrics.libraryDiameter)
        val bounds = circles.fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(bounds[0].width, bounds[1].width, 0.1f)
        assertEquals(bounds[0].height, bounds[1].height, 0.1f)
        compose.onNodeWithTag("apple-category-back-button").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun genericBackMatchesGenericActionCircleDiameter() {
        compose.setContent {
            BitChordTheme {
                ArtworkPageBackButton(onClick = {}, hazeState = null)
            }
        }
        compose.onNodeWithTag("apple-toolbar-back-button", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("apple-library-floating-circle", useUnmergedTree = true)
            .assertWidthIsEqualTo(40.dp)
    }

    @Test fun detailOnlyCircularActionAlsoUsesFortyDp() {
        compose.setContent {
            BitChordTheme {
                CircleIconButton(
                    icon = Icons.Rounded.Search,
                    contentDescription = "Detail action",
                    onClick = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Detail action")
            .assertWidthIsEqualTo(40.dp)
    }

    @Test fun completedRefreshRetainsPuckDuringExitAndRemovesItAfterFade() {
        val refreshing = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            BitChordTheme {
                RefreshPuck(
                    refreshing = refreshing.value,
                    pullFraction = { if (refreshing.value) 1f else 0f },
                    reduceMotion = false,
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("apple-refresh-puck", useUnmergedTree = true).assertExists()
        compose.runOnIdle { refreshing.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("apple-refresh-puck", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(500L)
        compose.onNodeWithTag("apple-refresh-puck", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun reducedMotionRefreshEndsWithoutFade() {
        val refreshing = mutableStateOf(true)
        compose.setContent {
            BitChordTheme {
                RefreshPuck(
                    refreshing = refreshing.value,
                    pullFraction = { if (refreshing.value) 1f else 0f },
                    reduceMotion = true,
                )
            }
        }
        compose.onNodeWithTag("apple-refresh-puck", useUnmergedTree = true).assertExists()
        compose.runOnIdle { refreshing.value = false }
        compose.onNodeWithTag("apple-refresh-puck", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun unavailableSecondaryActionsRemainDisabledWithSharedSurface() {
        compose.setContent {
            BitChordTheme {
                Row {
                    CircleIconButton(
                        icon = Icons.Rounded.Search,
                        contentDescription = "Unavailable circle",
                        onClick = {},
                        enabled = false,
                    )
                    ActionPill(
                        icon = Icons.Rounded.Search,
                        label = "Unavailable labeled action",
                        onClick = {},
                        filled = false,
                        enabled = false,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Unavailable circle").assertIsNotEnabled()
        compose.onNodeWithText("Unavailable labeled action").assertIsNotEnabled()
    }

}
