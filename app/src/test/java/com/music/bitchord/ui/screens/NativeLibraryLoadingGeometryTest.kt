package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Library loaders use the remaining page, not an 88dp strip under the header. */
// CI revalidation marker: no change to layout assertions or test behavior.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeLibraryLoadingGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun categoryLoadingReservesMostOfTheAvailableViewport() {
        compose.setContent {
            LazyColumn(Modifier.size(360.dp, 700.dp).testTag("native-loading-test-viewport")) {
                item {
                    NativeLoadingSpinner(modifier = Modifier.fillParentMaxHeight(0.68f))
                }
            }
        }
        val bounds = compose.onNodeWithTag("native-library-loading")
            .getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("native-loading-test-viewport")
            .getUnclippedBoundsInRoot()
        // The Robolectric host may constrain the requested 700dp surface.
        // Compare against the real available viewport, not its ideal size.
        val availableHeight = viewport.bottom - viewport.top
        val loaderHeight = bounds.bottom - bounds.top
        assertTrue(
            "Loader must fill at least 68% of the viewport; viewport=$viewport loader=$bounds",
            loaderHeight >= availableHeight * 0.68f - 1.dp,
        )
        val spinner = compose.onNodeWithTag("native-library-loading-indicator")
            .getUnclippedBoundsInRoot()
        assertEquals(((bounds.left + bounds.right) / 2f).value, ((spinner.left + spinner.right) / 2f).value, 1f)
        assertEquals(((bounds.top + bounds.bottom) / 2f).value, ((spinner.top + spinner.bottom) / 2f).value, 1f)
    }

    @Test fun shortInlineLoaderStillPreservesMinimumReadableHeight() {
        compose.setContent {
            Box(Modifier.size(360.dp, 150.dp)) {
                NativeLoadingSpinner()
            }
        }
        val bounds = compose.onNodeWithTag("native-library-loading")
            .getUnclippedBoundsInRoot()
        assertTrue((bounds.bottom - bounds.top) >= 88.dp)
        val spinner = compose.onNodeWithTag("native-library-loading-indicator")
            .getUnclippedBoundsInRoot()
        assertEquals(((bounds.left + bounds.right) / 2f).value, ((spinner.left + spinner.right) / 2f).value, 1f)
        assertEquals(((bounds.top + bounds.bottom) / 2f).value, ((spinner.top + spinner.bottom) / 2f).value, 1f)
    }
}
