package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzRecordingRecommendation
import com.music.bitchord.ui.canonicalPersonalRecommendationShelf
import com.music.bitchord.ui.canonicalCommunityCardIsMetadataOnly
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import com.music.bitchord.data.model.PlaybackSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalPersonalRecommendationShelfTest {
    private val valid = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val other = "22ad712e-ce73-9f0c-4e97-9f0e53144411"
    private val forYou = "For you"

    @Test fun onlyIndependentlyVerifiedRowsBecomeNavigationOnlyCards() {
        val rows = listOf(CanonicalCatalogSearchRow(
            musicBrainzId = valid, kind = CanonicalSearchKind.RECORDING,
            title = "Spring Day", subtitle = "BTS",
        ))
        val ranks = listOf(
            ListenBrainzRecordingRecommendation(other, 30.0),
            ListenBrainzRecordingRecommendation(valid, 10.0),
        )
        val shelf = canonicalPersonalRecommendationShelf(rows, ranks, forYou)
        assertEquals(forYou, shelf?.title)
        assertEquals(1, shelf!!.items.size)
        assertEquals("catalog:RECORDING:$valid", shelf.items.single().browseId)
        assertNull(shelf.items.single().videoId)
        assertTrue(canonicalCommunityCardIsMetadataOnly(
            PlaybackSourceType.EXPLORE, forYou, forYou, shelf.items.single().browseId,
        ))
        assertFalse(canonicalCommunityCardIsMetadataOnly(
            PlaybackSourceType.HOME, forYou, forYou, shelf.items.single().browseId,
        ))
    }

    @Test fun noVerifiedRowsMeansNoInventedShelf() {
        val ranks = listOf(ListenBrainzRecordingRecommendation(valid, 9.5))
        assertNull(canonicalPersonalRecommendationShelf(emptyList(), ranks, forYou))
    }
}
