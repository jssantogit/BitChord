package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PartyMemberRemoveActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun removeMemberActionRetains44dpTargetAndHostCallback() {
        var kicks = 0
        compose.setContent {
            MaterialTheme {
                PartyMemberRemoveAction(label = "Remover", onClick = { kicks++ })
            }
        }
        compose.onNodeWithTag("party-member-remove-action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(44.dp)
        compose.onNodeWithText("Remover").performClick()
        compose.runOnIdle { assertEquals(1, kicks) }
    }
}
