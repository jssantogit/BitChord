package com.music.bitchord.playback

import android.os.SystemClock
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics

/**
 * A MediaSession command being accepted is not an audible playback result.
 * The first audio-sink advancing callback is the positive evidence instead.
 * Never emit a mediaId, canonical identity, URL or account in telemetry.
 */
internal class SmartCollectionAudibleCorrelator(
    private val nowMillis: () -> Long,
    private val report: (attempt: String, stage: String) -> Unit,
    private val timeoutMillis: Long = 30_000L,
) {
    private data class Pending(val attempt: String, val mediaId: String, val started: Long)
    private var pending: Pending? = null

    fun begin(attempt: String, mediaId: String) {
        complete("superseded")
        if (attempt.isBlank() || mediaId.isBlank()) return
        pending = Pending(attempt, mediaId, nowMillis())
    }

    fun onAudioPositionAdvancing(mediaId: String?) {
        val current = pending ?: return
        if (nowMillis() - current.started > timeoutMillis) {
            complete("timed_out")
            return
        }
        if (mediaId == current.mediaId) complete("audible")
    }

    fun onPlaybackError() = complete("player_error")
    fun onPlaybackStopped() = complete("playback_stopped")

    private fun complete(stage: String) {
        val current = pending ?: return
        pending = null
        report(current.attempt, stage)
    }
}

/**
 * Only typed, bounded, privacy-safe stage evidence leaves the service.
 * Plans carry an ephemeral traceRef and are never serialized to the Session Bundle.
 */
internal object SmartCollectionPlaybackTrace {
    private val audible = SmartCollectionAudibleCorrelator(
        nowMillis = SystemClock::elapsedRealtime,
        report = { attempt, stage ->
            record(attempt, stage, "PLAY_NOW")
        },
    )

    fun stage(plan: CanonicalCollectionIntentPlan, stage: String, reason: String = "none") {
        plan.traceRef?.let { record(it, stage, plan.intent.name, reason) }
    }

    fun expectAudible(plan: CanonicalCollectionIntentPlan, mediaId: String?) {
        if (plan.intent == CanonicalCollectionIntent.PLAY_NOW &&
            plan.traceRef != null && !mediaId.isNullOrBlank()
        ) {
            audible.begin(plan.traceRef, mediaId)
            stage(plan, "awaiting_audio_samples")
        }
    }

    fun onAudioPositionAdvancing(mediaId: String?) = audible.onAudioPositionAdvancing(mediaId)
    fun onPlayerError() = audible.onPlaybackError()
    fun onPlaybackStopped() = audible.onPlaybackStopped()

    private fun record(
        attempt: String, stage: String, intent: String, reason: String = "none",
    ) {
        runCatching {
            Diagnostics.event(
                category = DiagnosticCategory.PLAYER,
                name = "smart_collection_media3_v1",
                metadata = mapOf(
                    "attempt" to attempt,
                    "stage" to stage,
                    "intent" to intent,
                    "reason" to reason,
                ),
            )
        }
    }
}
