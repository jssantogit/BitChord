package com.music.bitchord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedLibraryToolbarPolicyTest {
    @Test fun browseFiltersBelongToCategoryViewButNotArtistDetail() {
        assertTrue(shouldShowUnifiedLibraryBrowseFilters(detailOpen = false))
        assertFalse(shouldShowUnifiedLibraryBrowseFilters(detailOpen = true))
    }
}
