package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Do not hide the account control by suppressing the global Home top bar. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class HomeTitleAccountAccessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeHeaderRetainsTappableProfileActionAlongsideItsNativeOverflow() {
        var profileClicks = 0
        compose.setContent {
            HomeTitle(
                title = "Início",
                trailing = {
                    Text(
                        "Perfil",
                        Modifier.testTag("home-profile-control")
                            .clickable { profileClicks++ },
                    )
                },
                actions = listOf(HomeHeaderAction("Configurações") {}),
            )
        }
        compose.onNodeWithTag("home-title").assertExists()
        compose.onNodeWithTag("home-overflow").assertExists()
        compose.onNodeWithTag("home-profile-control").performClick()
        compose.runOnIdle { assertEquals(1, profileClicks) }
    }
}
