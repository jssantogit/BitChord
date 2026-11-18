package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.ReleaseEntity
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The artist's verified release cards use the lifted canonical menu on hold.
 * Selection mode keeps ownership of its own gestures instead.
 */
class NativeArtistReleaseHoldTest {
    @Test fun holdingReleaseWithoutSelectionForwardsItsExactIdentity() {
        val release = ReleaseEntity("release-42", "EP title", "Artist", null)
        var passed: ReleaseEntity? = null
        val hold = nativeArtistReleaseHoldAction(
            selectionActive = false,
            release = release,
            onReleaseOptions = { passed = it },
        )
        hold?.invoke()
        assertSame(release, passed)
    }

    @Test fun holdingReleaseDuringSelectionDoesNotOpenAnUnrelatedMenu() {
        val release = ReleaseEntity("release-42", "EP title", "Artist", null)
        val hold = nativeArtistReleaseHoldAction(
            selectionActive = true,
            release = release,
            onReleaseOptions = { error("Must not open menu during selection") },
        )
        assertNull(hold)
    }
}
