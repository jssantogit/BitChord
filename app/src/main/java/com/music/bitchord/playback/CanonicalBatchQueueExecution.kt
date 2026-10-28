package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import java.util.UUID

/** Single captured session/timeline identity, never source URLs or credentials. */
internal data class CanonicalBatchQueueSnapshot(
    val currentIndex: Int,
    val contextGeneration: Long,
    val queueKeys: List<String>,
    val tiers: List<QueueTier>,
    val transitioning: Boolean,
)

internal enum class CanonicalBatchQueueFailure {
    EMPTY_SELECTION, PARTIAL_SELECTION, LIMIT_EXCEEDED,
    INVALID_QUEUE, STALE_SESSION, TRANSITION_ACTIVE, AVAILABILITY_CHANGED,
}

internal sealed interface CanonicalBatchQueueDecision {
    data class Apply(
        val insertionIndex: Int,
        val tiers: List<QueueTier>,
        val startPlayback: Boolean,
    ) : CanonicalBatchQueueDecision
    data class Reject(val reason: CanonicalBatchQueueFailure) : CanonicalBatchQueueDecision
}

/**
 * Existing QueueCoordinator owns insertion order; this layer only validates a
 * canonical batch and selects the tier/start semantics of the explicit command.
 */
internal object CanonicalBatchQueuePolicy {
    // One atomic Media3 insertion. Large enough for a long smart playlist,
    // bounded to avoid unlimited queue materialization on Android.
    const val MAX_BATCH_SIZE = 500

    fun decide(
        plan: CanonicalCollectionIntentPlan,
        expected: CanonicalBatchQueueSnapshot,
        actual: CanonicalBatchQueueSnapshot,
        freshSelections: List<CanonicalCollectionSelection>,
    ): CanonicalBatchQueueDecision {
        fun reject(reason: CanonicalBatchQueueFailure) = CanonicalBatchQueueDecision.Reject(reason)
        if (plan.rejected.isNotEmpty()) return reject(CanonicalBatchQueueFailure.PARTIAL_SELECTION)
        if (plan.selections.isEmpty()) return reject(CanonicalBatchQueueFailure.EMPTY_SELECTION)
        if (plan.selections.size > MAX_BATCH_SIZE) return reject(CanonicalBatchQueueFailure.LIMIT_EXCEEDED)
        if (plan.selections.map { it.recordingId }.distinct().size != plan.selections.size) {
            return reject(CanonicalBatchQueueFailure.PARTIAL_SELECTION)
        }
        if (expected != actual) return reject(CanonicalBatchQueueFailure.STALE_SESSION)
        if (actual.queueKeys.size != actual.tiers.size ||
            (actual.tiers.isEmpty() && actual.currentIndex != -1) ||
            (actual.tiers.isNotEmpty() && actual.currentIndex !in actual.tiers.indices)
        ) return reject(CanonicalBatchQueueFailure.INVALID_QUEUE)
        if (actual.transitioning) return reject(CanonicalBatchQueueFailure.TRANSITION_ACTIVE)
        if (freshSelections.map { it.identity } != plan.selections.map { it.identity }) {
            return reject(CanonicalBatchQueueFailure.AVAILABILITY_CHANGED)
        }

        val stubTimeline = actual.tiers.mapIndexed { index, tier ->
            Song(
                videoId = "canonical-batch-anchor-" + index,
                title = "",
                artist = "",
                thumbnailUrl = null,
                queueTier = tier,
            )
        }
        val startNow = plan.intent == CanonicalCollectionIntent.PLAY_NOW
        val insertion = if (startNow) {
            actual.currentIndex + 1
        } else {
            QueueCoordinator.findUserQueueInsertionIndex(
                timeline = stubTimeline,
                currentIndex = actual.currentIndex,
                isNext = plan.intent == CanonicalCollectionIntent.ADD_NEXT,
            )
        }
        val tiers = if (startNow) {
            listOf(QueueTier.CONTEXT) +
                List(plan.selections.size - 1) { QueueTier.USER_QUEUE }
        } else {
            List(plan.selections.size) { QueueTier.USER_QUEUE }
        }
        return CanonicalBatchQueueDecision.Apply(insertion, tiers, startNow)
    }
}

/**
 * Process-local one-shot token. A controller sends only this opaque token;
 * full canonical selections remain in Playback memory, never Bundle/URL.
 * Bounded lifetime count prevents stale Activity actions from growing memory.
 */
internal object CanonicalBatchQueueRequests {
    private const val MAX_PENDING = 64
    private val pending = LinkedHashMap<String, CanonicalCollectionIntentPlan>()

    @Synchronized fun submit(plan: CanonicalCollectionIntentPlan): String {
        while (pending.size >= MAX_PENDING) {
            val oldest = pending.keys.firstOrNull() ?: break
            pending.remove(oldest)
        }
        val token = UUID.randomUUID().toString()
        pending[token] = plan
        return token
    }

    @Synchronized fun take(token: String): CanonicalCollectionIntentPlan? = pending.remove(token)
}

internal const val ACTION_EXECUTE_CANONICAL_BATCH =
    "com.music.bitchord.action.EXECUTE_CANONICAL_BATCH"
internal const val EXTRA_CANONICAL_BATCH_TOKEN = "bitchord.canonicalBatch.token"
