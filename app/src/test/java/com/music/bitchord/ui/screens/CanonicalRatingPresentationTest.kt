package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalRatingAssetRow
import com.music.bitchord.ui.components.RatingDisplayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalRatingPresentationTest {
    private val provider = "opensubsonic:account-one"
    private val otherProvider = "opensubsonic:account-two"
    private val scope = "scope-one"

    private fun asset(
        recording: String = "recording-one",
        id: String = "asset-one",
        source: String = provider,
        uri: String? = null,
    ) = CanonicalRatingAssetRow(
        recordingId = recording,
        mediaAssetId = id,
        providerKey = source,
        uri = uri,
        path = null,
    )

    @Test
    fun remoteFiveAndThreeAreSeparateRealNumericRatingsNotFavoriteFlags() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset(), asset("recording-two", "asset-two")),
            localValues = emptyMap(),
            remote = listOf(
                CanonicalRemoteRatingInput("recording-one", "asset-one", provider, scope, 5, starred = false),
                CanonicalRemoteRatingInput("recording-two", "asset-two", provider, scope, 3, starred = true),
            ),
            activeProvider = provider,
            activeScope = scope,
        )
        assertEquals(5, result.byMediaAssetId["asset-one"]?.remoteValue)
        assertEquals(3, result.byMediaAssetId["asset-two"]?.remoteValue)
        assertEquals(RatingDisplayKind.RATED, result.byMediaAssetId["asset-two"]?.kind)
    }

    @Test
    fun localAndRemoteConflictRemainsVisibleWithoutOverwritingEitherSource() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset()),
            localValues = mapOf("recording-one" to 4.5),
            remote = listOf(CanonicalRemoteRatingInput("recording-one", "asset-one", provider, scope, 3, false)),
            activeProvider = provider,
            activeScope = scope,
        )
        val display = result.byMediaAssetId.getValue("asset-one")
        assertEquals(4.5, display.localValue!!, 0.0)
        assertEquals(3, display.remoteValue)
        assertEquals(RatingDisplayKind.CONFLICT, display.kind)
    }

    @Test
    fun starredAloneNeverCreatesFiveStars() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset()),
            localValues = emptyMap(),
            remote = listOf(CanonicalRemoteRatingInput("recording-one", "asset-one", provider, scope, null, true)),
            activeProvider = provider,
            activeScope = scope,
        )
        assertNull(result.byMediaAssetId["asset-one"])
    }

    @Test
    fun foreignAccountAndOldScopeCannotLeakRemoteRatings() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset(), asset("recording-two", "foreign-asset", otherProvider)),
            localValues = emptyMap(),
            remote = listOf(
                CanonicalRemoteRatingInput("recording-one", "asset-one", provider, "old-scope", 5, false),
                CanonicalRemoteRatingInput("recording-two", "foreign-asset", otherProvider, scope, 5, false),
            ),
            activeProvider = provider,
            activeScope = scope,
        )
        assertTrue(result.byMediaAssetId.isEmpty())
    }

    @Test
    fun localOnlyAndNoRatingBehaveWithoutInventedRemoteNotes() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(
                asset(source = "local-mediastore", uri = "content://media/audio/1"),
                asset("unrated", "asset-unrated", "local-mediastore", "content://media/audio/2"),
            ),
            localValues = mapOf("recording-one" to 2.0),
            remote = emptyList(),
            activeProvider = null,
            activeScope = null,
        )
        assertEquals(2.0, result.bySongKey["content://media/audio/1"]?.localValue!!, 0.0)
        assertFalse(result.bySongKey.containsKey("content://media/audio/2"))
    }

    @Test
    fun invalidNumbersNeverBecomeStars() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset()),
            localValues = mapOf("recording-one" to 100.0),
            remote = listOf(CanonicalRemoteRatingInput("recording-one", "asset-one", provider, scope, 7, false)),
            activeProvider = provider,
            activeScope = scope,
        )
        assertTrue(result.byMediaAssetId.isEmpty())
    }
}
