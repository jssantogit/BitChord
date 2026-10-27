package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** #196: Home is a title plus an actionable overflow, not a stack of branding. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class HomeHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeTitleIsStartAlignedAndOldListenNowHeadingIsAbsent() {
        compose.setContent {
            MaterialTheme {
                HomeTitle(
                    title = "Início",
                    actions = listOf(HomeHeaderAction("Configurações") {}),
                )
            }
        }
        compose.onNodeWithTag("home-title").assertIsDisplayed()
        compose.onNodeWithText("Início").assertIsDisplayed()
        compose.onNodeWithText("Ouvir agora").assertDoesNotExist()
        compose.onNodeWithTag("home-overflow").assertIsDisplayed()
    }

    @Test fun overflowOpensExistingSettingsAndDownloadsActions() {
        val activated = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                HomeTitle(
                    title = "Início",
                    actions = listOf(
                        HomeHeaderAction("Configurações") { activated += "settings" },
                        HomeHeaderAction("Downloads") { activated += "downloads" },
                    ),
                )
            }
        }
        compose.onNodeWithTag("home-overflow").performClick()
        compose.onNodeWithText("Configurações").performClick()
        compose.runOnIdle { assertEquals(listOf("settings"), activated) }
        compose.onNodeWithTag("home-overflow").performClick()
        compose.onNodeWithText("Downloads").performClick()
        compose.runOnIdle { assertEquals(listOf("settings", "downloads"), activated) }
    }
}
