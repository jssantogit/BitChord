package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.provider.RemoteNumericRating
import com.music.bitchord.data.catalog.provider.RemoteNumericRatingKind
import com.music.bitchord.data.catalog.provider.RemoteStarredKind
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingAvailability
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingConsumerItem
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingConsumerSnapshot
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingCoverage
import org.junit.Assert.assertEquals
import org.junit.Test

/** One current account's read-only evidence; no local rating writes. */
class SmartCollectionScopedAssetPreferenceTest {
    private fun row(id: String, asset: String, value: Int) = RemoteRatingConsumerItem(
        recordingId = id,
        mediaAssetId = asset,
        providerKey = "opensubsonic:current-account",
        scopeId = "current-scope",
        generation = 8L,
        numeric = RemoteNumericRating(RemoteNumericRatingKind.VALUE, value),
        starred = RemoteStarredKind.NOT_PROVIDED,
    )

    private fun snapshot(vararg items: RemoteRatingConsumerItem) = RemoteRatingConsumerSnapshot(
        availability = RemoteRatingAvailability.CACHED_COMPLETE,
        coverage = RemoteRatingCoverage(),
        items = items.toList(),
    )

    @Test fun numericFiveUniquelyIdentifiesRemoteAssetWhileFourIsExcluded() {
        val resolved = uniqueVerifiedRatedRemoteAssets(
            snapshot(row("five", "media5", 5), row("four", "media4", 4)), 5.0,
        )
        assertEquals(
            mapOf("five" to CanonicalBrowseMediaAsset("media5", "opensubsonic:current-account")),
            resolved,
        )
    }

    @Test fun twoDistinctRatedRemoteAssetsAreAmbiguousEvenForSameRecording() {
        assertEquals(
            emptyMap<String, CanonicalBrowseMediaAsset>(),
            uniqueVerifiedRatedRemoteAssets(
                snapshot(row("five", "copy-one", 5), row("five", "copy-two", 5)), 5.0,
            ),
        )
    }

    @Test fun currentAccountUnavailabilityRevokesEvenExistingCachedRatedRows() {
        val cached = snapshot(row("five", "media5", 5)).copy(
            availability = RemoteRatingAvailability.UNAVAILABLE,
        )
        assertEquals(
            emptyMap<String, CanonicalBrowseMediaAsset>(),
            uniqueVerifiedRatedRemoteAssets(cached, 5.0),
        )
    }

    @Test fun duplicateEvidenceForExactSameAssetDoesNotCreateFalseAmbiguity() {
        val evidence = row("five", "media5", 5)
        assertEquals(
            mapOf("five" to CanonicalBrowseMediaAsset("media5", "opensubsonic:current-account")),
            uniqueVerifiedRatedRemoteAssets(snapshot(evidence, evidence), 5.0),
        )
    }

    @Test fun starredOrClearedEvidenceDoesNotProvideAnAssetPreference() {
        val noNumeric = row("five", "starred-asset", 5).copy(
            numeric = RemoteNumericRating(RemoteNumericRatingKind.NOT_PROVIDED),
            starred = RemoteStarredKind.STARRED,
        )
        val cleared = row("other", "cleared-asset", 5).copy(
            numeric = RemoteNumericRating(RemoteNumericRatingKind.CLEARED_ZERO),
        )
        assertEquals(
            emptyMap<String, CanonicalBrowseMediaAsset>(),
            uniqueVerifiedRatedRemoteAssets(snapshot(noNumeric, cleared), 5.0),
        )
    }
}
