package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The Explore genre grid and each genre must keep separate frames on navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExploreCategoryContentTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rootGenreSwitchAndBackHaveDistinctContentOwners() {
        val selected = mutableStateOf<String?>(null)
        compose.setContent {
            ExploreCategoryContentTransition(
                category = selected.value,
                reduceMotion = false,
            ) { category ->
                Box(Modifier.size(200.dp).testTag(
                    if (category == null) "explore-grid" else "explore-genre-$category",
                ))
            }
        }
        compose.onNodeWithTag("explore-grid").assertExists()
        compose.runOnIdle { selected.value = "K-pop" }
        compose.onNodeWithTag("explore-genre-K-pop").assertExists()
        compose.onNodeWithTag("explore-grid").assertDoesNotExist()

        compose.runOnIdle { selected.value = "Jazz" }
        compose.onNodeWithTag("explore-genre-Jazz").assertExists()
        compose.onNodeWithTag("explore-genre-K-pop").assertDoesNotExist()

        compose.runOnIdle { selected.value = null }
        compose.onNodeWithTag("explore-grid").assertExists()
        compose.onNodeWithTag("explore-genre-Jazz").assertDoesNotExist()
    }

    @Test fun exploreMotionIsolatesRetiredGenresAndHasNoSizeSpring() {
        val motion = exploreCategoryContentTransform(reduceMotion = false)
        org.junit.Assert.assertNull(motion.sizeTransform)
        org.junit.Assert.assertNotEquals(
            androidx.compose.animation.EnterTransition.None, motion.targetContentEnter,
        )
        org.junit.Assert.assertNotEquals(
            androidx.compose.animation.ExitTransition.None, motion.initialContentExit,
        )
        val reduced = exploreCategoryContentTransform(reduceMotion = true)
        org.junit.Assert.assertNull(reduced.sizeTransform)
        org.junit.Assert.assertEquals(
            androidx.compose.animation.EnterTransition.None, reduced.targetContentEnter,
        )
        org.junit.Assert.assertEquals(
            androidx.compose.animation.ExitTransition.None, reduced.initialContentExit,
        )
    }

    @Test fun reducedMotionReplacesCategoryImmediately() {
        val selected = mutableStateOf<String?>(null)
        compose.setContent {
            ExploreCategoryContentTransition(category = selected.value, reduceMotion = true) { category ->
                Box(Modifier.size(200.dp).testTag(
                    if (category == null) "explore-grid" else "explore-genre-$category",
                ))
            }
        }
        compose.onNodeWithTag("explore-grid").assertExists()
        compose.runOnIdle { selected.value = "Jazz" }
        compose.onNodeWithTag("explore-genre-Jazz").assertExists()
        compose.onNodeWithTag("explore-grid").assertDoesNotExist()
    }
}
