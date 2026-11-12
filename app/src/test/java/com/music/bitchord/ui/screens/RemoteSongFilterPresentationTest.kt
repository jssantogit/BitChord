package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The remote library filter uses native trigger but retains its selection callback. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RemoteSongFilterPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeFilterTriggerOpensAndResetsToAllSongs() {
        var picks = 0
        var last: String? = "not-called"
        compose.setContent {
            MaterialTheme {
                RemoteSongSavedFilterMenu(
                    selectedName = null,
                    options = emptyList(),
                    selectedFilterId = null,
                    onSelect = { last = it; picks++ },
                )
            }
        }
        compose.onNodeWithTag("native-inline-text-action")
            .assertHeightIsAtLeast(44.dp)
        compose.onNodeWithText("Filtro: Todas").performClick()
        compose.onNodeWithText("Todas as músicas").performClick()
        compose.runOnIdle { assertEquals(1, picks); assertNull(last) }
    }
}
