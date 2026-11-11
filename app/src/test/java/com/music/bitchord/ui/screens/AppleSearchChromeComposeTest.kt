package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.SearchField
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppleSearchChromeComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appleSearchBackgroundAndBackTargetFollowVerified48DpGeometry() {
        compose.setContent {
            MaterialTheme {
                SearchField(
                    query = "newjeans", onQueryChange = {}, onSubmit = {},
                    appleSearchChrome = true,
                )
            }
        }
        compose.onNodeWithTag("apple-search-field").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("apple-search-back").assertWidthIsEqualTo(48.dp)
        compose.onNodeWithTag("apple-search-back").assertHeightIsEqualTo(48.dp)
    }
}
