package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentLocalAdditionsRefreshPolicyTest {
    @Test fun emptySnapshotBeforeLocalIndexIsNotFinal() {
        assertTrue(shouldRequeryRecentLocalAdditions(emptyList(), explicitRefresh = false))
        assertTrue(shouldRequeryRecentLocalAdditions(null, explicitRefresh = false))
    }

    @Test fun populatedCacheAvoidsRequeryUntilUserRefreshes() {
        assertFalse(shouldRequeryRecentLocalAdditions(listOf("recording"), explicitRefresh = false))
        assertTrue(shouldRequeryRecentLocalAdditions(listOf("recording"), explicitRefresh = true))
    }
}
