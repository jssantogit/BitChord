package com.music.bitchord

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Update notices share the native 40dp icon action without losing the dialog callback. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeUpdateTopBarActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun updateNoticeExposesNativeFortyDpClickableAction() {
        var opens = 0
        compose.setContent {
            BitChordTheme {
                NativeUpdateTopBarAction(version = "9.2", onClick = { opens++ })
            }
        }
        compose.onNodeWithTag("native-update-topbar-action")
            .assertWidthIsEqualTo(40.dp)
            .assertHeightIsEqualTo(40.dp)
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
