package com.music.bitchord

import com.music.bitchord.data.listening.CanonicalSmartFilterContext
import com.music.bitchord.data.settings.LibraryViewType
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleLibraryCategoryPresentationTest {
    @Test fun appleReferenceStartsAlbumsInGridAndSongsArtistsInList() {
        assertEquals(LibraryViewType.GRID, appleLibraryDefaultViewType(CanonicalSmartFilterContext.ALBUMS))
        assertEquals(LibraryViewType.LIST, appleLibraryDefaultViewType(CanonicalSmartFilterContext.SONGS))
        assertEquals(LibraryViewType.LIST, appleLibraryDefaultViewType(CanonicalSmartFilterContext.ARTISTS))
    }

    @Test fun manualViewPreferencesSurviveLeavingAndReturningToCategory() {
        val controls = UnifiedLibraryUiControls()
        controls.selectCategory(CanonicalSmartFilterContext.ALBUMS)
        assertEquals(LibraryViewType.GRID, controls.viewType)
        controls.viewType = LibraryViewType.LIST
        controls.selectCategory(CanonicalSmartFilterContext.SONGS)
        assertEquals(LibraryViewType.LIST, controls.viewType)
        controls.viewType = LibraryViewType.GRID
        controls.selectCategory(CanonicalSmartFilterContext.ALBUMS)
        assertEquals(LibraryViewType.LIST, controls.viewType)
        controls.selectCategory(CanonicalSmartFilterContext.SONGS)
        assertEquals(LibraryViewType.GRID, controls.viewType)
    }
}
