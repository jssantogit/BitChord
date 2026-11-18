package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Artist details must keep their place on metadata refresh, but never reuse
 * the scroll offset of a different verified artist. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeCanonicalArtistScrollStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun refreshKeepsPositionButOpeningAnotherArtistStartsFresh() {
        val artistId = mutableStateOf<String?>("artist-001")
        val artistName = mutableStateOf("Same display")
        val refresh = mutableStateOf(0)
        var observed: androidx.compose.foundation.lazy.LazyListState? = null
        compose.setContent {
            val state = rememberNativeCanonicalArtistScrollState(
                artistId.value, artistName.value,
            )
            Text("Refresh ${refresh.value}")
            SideEffect { observed = state }
        }
        val first = observed
        compose.runOnIdle { refresh.value++ }
        compose.waitForIdle()
        assertSame(first, observed)
        // Two distinct verified ArtistIds can share the same display name.
        compose.runOnIdle { artistId.value = "artist-002" }
        compose.waitForIdle()
        assertNotSame(first, observed)
    }

    @Test fun unverifiedArtistIsKeyedByItsDisplayIdentity() {
        val artistName = mutableStateOf("First performer")
        var observed: androidx.compose.foundation.lazy.LazyListState? = null
        compose.setContent {
            val state = rememberNativeCanonicalArtistScrollState(null, artistName.value)
            SideEffect { observed = state }
        }
        val first = observed
        compose.runOnIdle { artistName.value = "Second performer" }
        compose.waitForIdle()
        assertNotSame(first, observed)
    }
}
