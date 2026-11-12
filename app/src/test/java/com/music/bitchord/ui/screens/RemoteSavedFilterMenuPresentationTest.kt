package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Filter
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RemoteSavedFilterMenuPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longSavedFilterListUsesCompactPopupAndRetainsEveryChoice() {
        val rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, emptyList())
        val options = (1..24).map { n ->
            SavedSmartPlaylistV2Filter("filter-$n", "Filtro $n", rules)
        }
        var chosen: String? = "filter-12"
        compose.setContent {
            MaterialTheme {
                RemoteSongSavedFilterMenu(
                    selectedName = "Filtro 12",
                    options = options,
                    selectedFilterId = chosen,
                    onSelect = { chosen = it },
                )
            }
        }
        compose.onNodeWithText("Filtro: Filtro 12").performClick()
        compose.onNodeWithTag("native-compact-overflow").assertExists()
        compose.onNodeWithText("Filtro 12").assertIsSelected()
        compose.onNodeWithText("Filtro 24").assertExists()
        compose.onNodeWithText("Todas as músicas").performClick()
        compose.runOnIdle { assertEquals(null, chosen) }
    }

    @Test fun remoteBrowseBackUsesFortyDpNativeVectorAndPreservesNavigation() {
        var backs = 0
        compose.setContent {
            MaterialTheme {
                RemoteBrowseTopBar(
                    title = "Remote library",
                    onBack = { backs++ },
                )
            }
        }
        val label = RuntimeEnvironment.getApplication().getString(com.music.bitchord.R.string.remote_library_back)
        compose.onNodeWithContentDescription(label)
            .assertWidthIsEqualTo(40.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }
}
