package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlan
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import java.util.Locale

/**
 * Only an already-claimed Recording MBID with one revalidated playable asset
 * may cross from public MusicBrainz discovery into the canonical queue.
 */
internal class MusicBrainzCatalogPlaybackGate(
    private val trustedRecordingId: suspend (String) -> String?,
    private val browse: suspend () -> CanonicalBrowseLibrary,
    private val planner: CanonicalCollectionIntentPlanner,
) {
    suspend fun plan(mbid: String, intent: CanonicalCollectionIntent): CanonicalCollectionIntentPlan? {
        val id = mbid.trim().lowercase(Locale.ROOT).takeIf(MBID::matches) ?: return null
        val recordingId = trustedRecordingId(id)?.takeIf(String::isNotBlank) ?: return null
        val plan = planner.preview(
            recordingIds = listOf(recordingId),
            intent = intent,
            library = browse(),
            preferSingleLocalCopy = true,
        )
        return plan.takeIf { it.rejected.isEmpty() && it.selections.size == 1 }
    }

    private companion object {
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
