package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Refreshing a release should not jump, but opening another release must not
 * inherit the previous one's scroll position. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeAlbumDetailScrollStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stableReleaseIdentityKeepsListStateAndNewReleaseResetsIt() {
        val albumId = mutableStateOf("release-a")
        val refresh = mutableStateOf(0)
        var current: androidx.compose.foundation.lazy.LazyListState? = null
        compose.setContent {
            val state = rememberNativeAlbumDetailListState(albumId.value)
            Text("Release ${albumId.value} refresh ${refresh.value}")
            SideEffect { current = state }
        }
        val first = current
        compose.runOnIdle { refresh.value += 1 }
        compose.waitForIdle()
        assertSame(first, current)
        compose.runOnIdle { albumId.value = "release-b" }
        compose.waitForIdle()
        assertNotSame(first, current)
    }
}
