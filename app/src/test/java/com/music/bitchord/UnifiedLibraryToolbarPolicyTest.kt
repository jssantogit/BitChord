package com.music.bitchord

import com.music.bitchord.data.listening.CanonicalSmartFilterContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedLibraryToolbarPolicyTest {
    @Test fun onlyAlbumDetailsUseAlbumToolbarInsteadOfProfileAndRootFilters() {
        assertTrue(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.ALBUMS, detailOpen = true,
        ))
        assertFalse(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.ALBUMS, detailOpen = false,
        ))
        assertFalse(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.ARTISTS, detailOpen = true,
        ))
        assertFalse(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.SONGS, detailOpen = true,
        ))
        assertFalse(shouldUseUnifiedAlbumDetailToolbar(null, detailOpen = true))
    }

    @Test fun browseFiltersBelongToCategoryViewButNotArtistDetail() {
        assertTrue(shouldShowUnifiedLibraryBrowseFilters(detailOpen = false))
        assertFalse(shouldShowUnifiedLibraryBrowseFilters(detailOpen = true))
    }
}
