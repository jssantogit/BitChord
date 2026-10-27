package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

    @Test fun everyExistingGroupHasExactlyOneDestination() {
        val allSections = SettingsNavigation.groups.flatMap { it.sections }
        assertEquals(SettingsSection.entries.size, allSections.size)
        assertEquals(SettingsSection.entries.toSet(), allSections.toSet())
        assertTrue(SettingsNavigation.groups.all { it.sections.isNotEmpty() })
    }

    @Test fun selectingAppearanceNavigatesWithoutChangingAPreference() {
        var opened: SettingsSection? = null
        compose.setContent {
            MaterialTheme {
                SettingsHome(onSelect = { opened = it })
            }
        }
        compose.onNodeWithTag("settings-category-APPEARANCE").performClick()
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
