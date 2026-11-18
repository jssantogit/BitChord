package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple-style category lists draw thin inset separators between entries,
 * never a dangling separator below their final visible item.
 */
class CanonicalLibraryRowDividerTest {
    @Test fun separatorOnlyAppearsBetweenVisibleEntries() {
        assertTrue(shouldDrawCanonicalLibraryRowDivider(index = 0, total = 2))
        assertFalse(shouldDrawCanonicalLibraryRowDivider(index = 1, total = 2))
        assertFalse(shouldDrawCanonicalLibraryRowDivider(index = 0, total = 1))
    }

    @Test fun emptyAndOutOfRangePositionsCannotProduceSeparators() {
        assertFalse(shouldDrawCanonicalLibraryRowDivider(index = 0, total = 0))
        assertFalse(shouldDrawCanonicalLibraryRowDivider(index = -1, total = 4))
        assertFalse(shouldDrawCanonicalLibraryRowDivider(index = 5, total = 4))
    }
}
