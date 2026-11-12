package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Download progress occupies the same 40dp top-bar slot as settings. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TopBarDownloadActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun downloadControlHasSame40dpSlotAndPreservesClick() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                TopBarDownloadAction(onClick = { opens++ }) {
                    Icon(Icons.Rounded.DownloadDone, contentDescription = "Downloads concluídos")
                }
            }
        }
        compose.onNodeWithTag("apple-topbar-download-action")
            .assertWidthIsEqualTo(40.dp)
            .assertHeightIsEqualTo(40.dp)
        compose.onNodeWithContentDescription("Downloads concluídos").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
