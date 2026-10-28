package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Issue #187: every old settings group must have a unique, discoverable route. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lyricsSummaryCountIsUnambiguousForSingularAndPlural() {
        assertEquals(null, lyricsSourcesCountValue(0))
        assertEquals("1", lyricsSourcesCountValue(1))
        assertEquals("8", lyricsSourcesCountValue(8))
    }

    @Test fun everyExistingGroupHasExactlyOneDestination() {
        val allSections = SettingsNavigation.groups.flatMap { it.sections }
        assertEquals(SettingsSection.entries.size, allSections.size)
        assertEquals(SettingsSection.entries.toSet(), allSections.toSet())
        assertTrue(SettingsNavigation.groups.all { it.sections.isNotEmpty() })
    }

    @Test fun destinationPagesDoNotDuplicateTheirHeadingAsGroupCaption() {
        assertEquals(null, settingsGroupCaption(isSearchResult = false, groupCaption = "LETRAS"))
        assertEquals(null, settingsGroupCaption(isSearchResult = false, groupCaption = "REPRODUÇÃO"))
        assertEquals("LETRAS", settingsGroupCaption(isSearchResult = true, groupCaption = "LETRAS"))
    }

    @Test fun playbackControlsBelongToMusicNotSystem() {
        val music = SettingsNavigation.groups.single { it.titleRes == com.music.bitchord.R.string.settings_nav_group_music }
        val system = SettingsNavigation.groups.single { it.titleRes == com.music.bitchord.R.string.settings_nav_group_system }
        assertTrue(SettingsSection.MISCELLANEOUS in music.sections)
        assertTrue(SettingsSection.MISCELLANEOUS !in system.sections)
    }

    @Test fun selectingAppearanceNavigatesWithoutChangingAPreference() {
        var opened: SettingsSection? = null
        compose.setContent {
            MaterialTheme {
                Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    SettingsHome(onSelect = { opened = it })
                }
            }
        }
        compose.onNodeWithTag("settings-category-APPEARANCE").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(SettingsSection.APPEARANCE, opened) }
    }

    @Test fun globalSearchResultKeepsOriginalSectionAndSettingLabel() {
        var destination: SettingsSection? = null
        var setting: String? = null
        compose.setContent {
            MaterialTheme {
                SettingsSearchDestination(
                    section = SettingsSection.PLAYBACK,
                    title = "Crossfade",
                    onNavigate = { section, title ->
                        destination = section
                        setting = title
                    },
                )
            }
        }
        compose.onNodeWithText("Crossfade").performClick()
        compose.runOnIdle {
            assertEquals(SettingsSection.PLAYBACK, destination)
            assertEquals("Crossfade", setting)
        }
    }
}
