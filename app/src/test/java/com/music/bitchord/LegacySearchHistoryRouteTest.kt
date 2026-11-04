package com.music.bitchord

import com.music.bitchord.ui.HistoricalTrackHistoryAction
import com.music.bitchord.ui.historicalTrackHistoryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySearchHistoryRouteTest {
    @Test fun opaqueYoutubeVideoIdsAreSearchOnly() {
        for (id in listOf("dQw4w9WgXcQ", "bitchord://watch?v=old", "https://youtu.be/old", "")) {
            assertEquals(HistoricalTrackHistoryAction.Search, historicalTrackHistoryAction(id))
        }
    }

    @Test fun realLocalUriKeepsLocalPlaybackWithoutYoutube() {
        val uri = "content://media/external/audio/media/151"
        assertEquals(HistoricalTrackHistoryAction.Local(uri), historicalTrackHistoryAction(uri))
    }

    @Test fun verifiedCanonicalRemoteUriKeepsProviderScopedIdentity() {
        val action = historicalTrackHistoryAction(
            "bitchord://canonical?recordingId=rec-1&mediaAssetId=asset-1&providerKey=opensubsonic%3Aone",
        )
        assertTrue(action is HistoricalTrackHistoryAction.Remote)
        val remote = action as HistoricalTrackHistoryAction.Remote
        assertEquals("rec-1", remote.request.recordingId)
        assertEquals("asset-1", remote.request.mediaAssetId)
        assertEquals("opensubsonic:one", remote.request.providerKey)
    }

    @Test fun historicalYoutubeCanonicalProviderIsNeverAcceptedForPlayback() {
        assertEquals(
            HistoricalTrackHistoryAction.Search,
            historicalTrackHistoryAction(
                "bitchord://canonical?recordingId=r&mediaAssetId=a&providerKey=youtube",
            ),
        )
        assertEquals(
            HistoricalTrackHistoryAction.Search,
            historicalTrackHistoryAction(
                "bitchord://canonical?recordingId=r&mediaAssetId=a&providerKey=jellyfin%3Aone",
            ),
        )
    }

    @Test fun unverifiedWebUriCannotTriggerPlayback() {
        assertEquals(
            HistoricalTrackHistoryAction.Search,
            historicalTrackHistoryAction("https://example.invalid/audio.mp3"),
        )
    }
}
