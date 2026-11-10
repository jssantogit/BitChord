package com.music.bitchord.ui.player

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * An external tap-open and a mini-player finger pull both move the same
 * sheet, but only the latter may be cancelled by a pointer/queue change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlayerSheetMotionPullOwnershipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelledUnownedPullCannotClosePlayerOpenedByTap() {
        val shown = mutableStateOf(false)
        val dock = PlayerDock()
        lateinit var motion: PlayerSheetMotion
        compose.setContent {
            val scope = rememberCoroutineScope()
            motion = remember {
                PlayerSheetMotion(
                    scope = scope,
                    dock = dock,
                    shown = { shown.value },
                    setShown = { shown.value = it },
                )
            }
        }
        compose.runOnIdle {
            dock.sheetTravel = 600f
            motion.laidOut = true
            motion.open()
            // A separate mini-player detector can finish late while the
            // tap-triggered open animation has already taken ownership.
            motion.drag(-80f)
            motion.cancel()
        }
        compose.mainClock.advanceTimeBy(3_000L)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("A cancelled non-owner must not close a tap-opened sheet", shown.value)
        }
    }

    @Test fun cancellingActualMiniPullStillClosesTheSheet() {
        val shown = mutableStateOf(false)
        val dock = PlayerDock()
        lateinit var motion: PlayerSheetMotion
        compose.setContent {
            val scope = rememberCoroutineScope()
            motion = remember {
                PlayerSheetMotion(
                    scope = scope,
                    dock = dock,
                    shown = { shown.value },
                    setShown = { shown.value = it },
                )
            }
        }
        compose.runOnIdle {
            dock.sheetTravel = 600f
            motion.laidOut = true
            motion.drag(-200f)
            assertTrue("The finger pull must show the sheet before release", shown.value)
            motion.cancel()
        }
        compose.mainClock.advanceTimeBy(3_000L)
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("The interrupted owner pull must return the sheet home", shown.value)
        }
    }
}
