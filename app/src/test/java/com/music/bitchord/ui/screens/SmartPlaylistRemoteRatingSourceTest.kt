package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.provider.RemoteNumericRating
import com.music.bitchord.data.catalog.provider.RemoteNumericRatingKind
import com.music.bitchord.data.catalog.provider.RemoteStarredKind
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingAvailability
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingConsumerItem
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingConsumerSnapshot
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingCoverage
import com.music.bitchord.data.listening.RemoteSmartPlaylistRatingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catalog numeric semantics remain read-only when passed to L6 smart playlists. */
class SmartPlaylistRemoteRatingSourceTest {
    private fun item(
        id: String,
        kind: RemoteNumericRatingKind,
        value: Int? = null,
        starred: RemoteStarredKind = RemoteStarredKind.NOT_STARRED,
    ) = RemoteRatingConsumerItem(
        recordingId = id,
        mediaAssetId = "asset:$id",
        providerKey = "opensubsonic:test",
        scopeId = "current-scope",
        generation = 7,
        numeric = RemoteNumericRating(kind, value),
        starred = starred,
    )

    @Test fun numericFiveIsPreservedButStarredOnlyNeverBecomesFive() {
        val snapshot = RemoteRatingConsumerSnapshot(
            availability = RemoteRatingAvailability.CACHED_COMPLETE,
            coverage = RemoteRatingCoverage(enumerated = 2, matched = 2),
            items = listOf(
                item("rec:five", RemoteNumericRatingKind.VALUE, 5),
                item("rec:starred", RemoteNumericRatingKind.NOT_PROVIDED,
                    starred = RemoteStarredKind.STARRED),
            ),
        )
        val mapped = mapRemoteRatingSnapshotForSmartPlaylist(snapshot)
        assertTrue(mapped.complete)
        assertEquals(5, mapped.evidence.single { it.recordingId == "rec:five" }.value)
        assertEquals(RemoteSmartPlaylistRatingKind.NOT_PROVIDED,
            mapped.evidence.single { it.recordingId == "rec:starred" }.kind)
        assertEquals(null, mapped.evidence.single { it.recordingId == "rec:starred" }.value)
    }

    @Test fun clearedAndInvalidAreNotSyntheticNumericRatings() {
        val mapped = mapRemoteRatingSnapshotForSmartPlaylist(
            RemoteRatingConsumerSnapshot(
                RemoteRatingAvailability.CACHED_COMPLETE,
                RemoteRatingCoverage(),
                listOf(
                    item("rec:cleared", RemoteNumericRatingKind.CLEARED_ZERO),
                    item("rec:invalid", RemoteNumericRatingKind.INVALID),
                ),
            ),
        )
        assertEquals(listOf(RemoteSmartPlaylistRatingKind.CLEARED,
            RemoteSmartPlaylistRatingKind.INVALID), mapped.evidence.map { it.kind })
        assertTrue(mapped.evidence.all { it.value == null })
    }

    @Test fun unavailableAccountCannotLeakPreviouslyCachedRowsToThePlanner() {
        val unavailable = RemoteRatingConsumerSnapshot(
            RemoteRatingAvailability.UNAVAILABLE,
            RemoteRatingCoverage(),
            listOf(item("rec:previous-account", RemoteNumericRatingKind.VALUE, 5)),
        )
        val mapped = mapRemoteRatingSnapshotForSmartPlaylist(unavailable)
        assertFalse(mapped.complete)
        assertTrue(mapped.evidence.isEmpty())
    }
}
