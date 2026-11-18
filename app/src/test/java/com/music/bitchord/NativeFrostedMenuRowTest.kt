package com.music.bitchord

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.music.bitchord.ui.components.TopBarAccountButton
import androidx.compose.ui.test.performClick
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Selected source/sort menu rows announce state and preserve callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeFrostedMenuRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedFilterIsDistinguishableAndCanStillBeClicked() {
        var clicks by mutableIntStateOf(0)
        compose.setContent {
            BitChordTheme {
                FrostedMenuRow(label = "Fonte local", checked = true, onClick = { clicks++ })
            }
        }
        compose.onNodeWithText("Fonte local").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun homeSettingsLauncherDelegatesToArtistStyleHostWithoutOpeningAnotherPopup() {
        var opens by mutableIntStateOf(0)
        compose.setContent {
            BitChordTheme {
                TopBarAccountButton(
                    account = null,
                    onClick = {},
                    hostedExpanded = false,
                    onShowHostedMenu = { opens++ },
                )
            }
        }
        compose.onNodeWithTag("apple-global-more-options").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        compose.onNodeWithTag("native-compact-overflow").assertDoesNotExist()
    }

    @Test fun frostedArtistActionsAlwaysHaveAnOpaqueNeutralBase() {
        assertEquals(Color(0xFF29292D), frostedMenuPanelOpaquePlate(isDark = true))
        assertEquals(Color(0xFFF5F5F7), frostedMenuPanelOpaquePlate(isDark = false))
        assertEquals(1f, frostedMenuPanelOpaquePlate(isDark = true).alpha)
        assertEquals(1f, frostedMenuPanelOpaquePlate(isDark = false).alpha)
    }

    @Test fun expandedOverflowHasSubtleFillAndNoSecondWhiteRing() {
        assertEquals(Color.Transparent,
            nativeCollectionOverflowActiveFill(expanded = false, ink = Color.White))
        assertEquals(Color.White.copy(alpha = 0.14f),
            nativeCollectionOverflowActiveFill(expanded = true, ink = Color.White))
        assertEquals(Color.Black.copy(alpha = 0.14f),
            nativeCollectionOverflowActiveFill(expanded = true, ink = Color.Black))
    }

    @Test fun rootSettingsAndArtistUseTheSameFrostedMenuWidth() {
        assertEquals(240.dp, FROSTED_SETTINGS_MENU_WIDTH)
    }

    @Test fun sharedArtistSettingsActionIsClickableAndNeverSelected() {
        var clicks by mutableIntStateOf(0)
        val label = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.settings)
        compose.setContent {
            BitChordTheme {
                FrostedSettingsAction { clicks++ }
            }
        }
        compose.onNodeWithText(label).assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun ordinaryMenuActionIsNotIncorrectlyMarkedSelected() {
        compose.setContent {
            BitChordTheme {
                FrostedMenuRow(label = "Visualizar em grade", checked = false, onClick = {})
            }
        }
        compose.onNodeWithText("Visualizar em grade").assertIsNotSelected()
    }
}
