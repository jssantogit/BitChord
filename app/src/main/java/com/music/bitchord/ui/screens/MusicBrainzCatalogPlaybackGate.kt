package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlan
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import com.music.bitchord.playback.CollectionIntentRejection
import java.util.Locale

/**
 * Only an already-claimed Recording MBID with one revalidated playable asset
 * may cross from public MusicBrainz discovery into the canonical queue.
 */
internal class MusicBrainzCatalogPlaybackGate(
    private val trustedRecordingId: suspend (String) -> String?,
    private val browse: suspend () -> CanonicalBrowseLibrary,
    private val planner: CanonicalCollectionIntentPlanner,
    /** Privacy-safe reason only; no MBIDs, provider URLs or user-entered titles. */
    private val traceOutcome: (String) -> Unit = {},
) {
    suspend fun plan(mbid: String, intent: CanonicalCollectionIntent): CanonicalCollectionIntentPlan? {
        val id = mbid.trim().lowercase(Locale.ROOT).takeIf(MBID::matches) ?: run {
            traceOutcome("invalid_mbid")
            return null
        }
        val recordingId = trustedRecordingId(id)?.takeIf(String::isNotBlank) ?: run {
            traceOutcome("unlinked_recording")
            return null
        }
        val plan = planner.preview(
            recordingIds = listOf(recordingId),
            intent = intent,
            library = browse(),
            preferSingleLocalCopy = true,
        )
        val allowed = plan.rejected.isEmpty() && plan.selections.size == 1
        val reason = if (allowed) "ready" else when (plan.rejected.firstOrNull()?.reason) {
            CollectionIntentRejection.AMBIGUOUS_ASSET -> "ambiguous_asset"
            CollectionIntentRejection.NO_ASSET -> "no_asset"
            CollectionIntentRejection.RECORDING_UNAVAILABLE -> "recording_unavailable"
            CollectionIntentRejection.AMBIGUOUS_RECORDING -> "ambiguous_recording"
            CollectionIntentRejection.INVALID_ASSET -> "invalid_asset"
            CollectionIntentRejection.RESOLUTION_MISMATCH -> "resolution_mismatch"
            else -> "not_ready"
        }
        traceOutcome(reason)
        return plan.takeIf { allowed }
    }

    private companion object {
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
