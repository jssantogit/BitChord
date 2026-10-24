package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same provider filter used for playback must discover canonical local assets. */
class SmartCollectionBrowseProviderTest {
    @Test fun offlineLocalOnlySmartPlaylistDoesNotRequireNavidromeAccount() {
        assertEquals(listOf("local-mediastore"), canonicalBrowseProviderKeys(emptyList()))
    }

    @Test fun localAndNavidromeRenditionsAreBothVisibleForCanonicalPlanning() {
        assertEquals(
            listOf("local-mediastore", "opensubsonic:fixture"),
            canonicalBrowseProviderKeys(listOf("opensubsonic:fixture")),
        )
    }

    @Test fun configuredRemoteProviderKeysAreDistinctAndDeterministic() {
        assertEquals(
            listOf("local-mediastore", "opensubsonic:a", "opensubsonic:b"),
            canonicalBrowseProviderKeys(listOf(
                "opensubsonic:b", "opensubsonic:a", "opensubsonic:a",
            )),
        )
    }
}
