package com.music.bitchord

import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.ui.shouldStartAppleHomeEditorial
import com.music.bitchord.ui.editorialPrimaryMiss
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure fallback state: dispatch may not bypass a pending preferred provider. */
class CanonicalHomeEditorialDispatchTest {
    @Test fun noSecondaryWhilePrimaryHasNotAnswered() {
        assertFalse(editorialPrimaryMiss<String>(null))
        assertFalse(editorialPrimaryMiss<String>(UiState.Loading))
        assertFalse(editorialPrimaryMiss(UiState.Success(listOf("itunes:br:album:99"))))
    }

    @Test fun nativeAppleFallbackUsesVisibleMbProductsNotUnrenderableRawRows() {
        val mb = BuiltInProviderKeys.MUSICBRAINZ
        val apple = BuiltInProviderKeys.ITUNES
        val nonCatalog = HomeShelf("Releases", listOf(
            ShelfItem("Personal", "Owner", null, null, "downloads:album:42"),
        ))
        assertTrue(shouldStartAppleHomeEditorial(
            listOf(mb, apple), false, listOf(nonCatalog), "Releases",
        ))
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(mb, apple), true, listOf(nonCatalog), "Releases",
        ))
        val nativeMb = HomeShelf("Releases", listOf(
            ShelfItem("Exact MB release", "Artist", null, null,
                "catalog:RELEASE_GROUP:11111111-1111-4111-8111-111111111111"),
        ))
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(mb, apple), false, listOf(nativeMb), "Releases",
        ))
        assertTrue(shouldStartAppleHomeEditorial(
            listOf(apple, mb), false, listOf(nativeMb), "Releases",
        ))
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(mb), false, emptyList(), "Releases",
        ))
    }

    @Test fun loadingPrimaryHoldsFallbackEvenWhenPriorCompositionSawIdleState() {
        val mb = BuiltInProviderKeys.MUSICBRAINZ
        val apple = BuiltInProviderKeys.ITUNES
        // The dispatcher reevaluates the current loading flow after
        // initiating the MB request; a stale Idle=false snapshot cannot
        // substitute for this still-loading state.
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(mb, apple), mbLoading = true,
            visibleHomeShelves = emptyList(), releasesHeading = "Releases",
        ))
        assertTrue(shouldStartAppleHomeEditorial(
            listOf(mb, apple), mbLoading = false,
            visibleHomeShelves = emptyList(), releasesHeading = "Releases",
        ))
    }

    @Test fun secondaryAllowedAfterRealEmptyOrUnavailablePrimary() {
        assertTrue(editorialPrimaryMiss(UiState.Success(emptyList<String>())))
        assertTrue(editorialPrimaryMiss<String>(UiState.Error("unavailable")))
    }
}
