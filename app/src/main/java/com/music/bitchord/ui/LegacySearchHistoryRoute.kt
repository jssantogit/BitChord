package com.music.bitchord.ui

import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.CanonicalRemotePlaybackUri

/**
 * Search history stores old opaque video IDs. Those are archival references,
 * not proof of a currently owned MediaAsset: never hand them to playback.
 */
internal sealed interface HistoricalTrackHistoryAction {
    data object Search : HistoricalTrackHistoryAction
    data class Local(val uri: String) : HistoricalTrackHistoryAction
    data class Remote(
        val request: CanonicalRemotePlaybackRequest,
    ) : HistoricalTrackHistoryAction
}

internal fun historicalTrackHistoryAction(id: String): HistoricalTrackHistoryAction {
    val local = id.startsWith("content://", ignoreCase = true) ||
        id.startsWith("file://", ignoreCase = true)
    if (local) return HistoricalTrackHistoryAction.Local(id)
    return CanonicalRemotePlaybackUri.decode(id)
        ?.takeIf { request ->
            // This is the only credential-resolving remote transport currently
            // implemented. Never resurrect a legacy provider from history.
            request.providerKey.startsWith("opensubsonic:")
        }
        ?.let(HistoricalTrackHistoryAction::Remote)
        ?: HistoricalTrackHistoryAction.Search
}
