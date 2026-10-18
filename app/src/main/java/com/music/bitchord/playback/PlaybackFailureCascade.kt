package com.music.bitchord.playback

/** Repeat preference as seen by the runtime-only failure cascade. */
internal enum class FailureRepeatMode {
    OFF,
    ONE,
    ALL,
}

/**
 * One materialized queue occurrence.
 *
 * [key] is the stable identity of the logical queue entry for the lifetime of a
 * cascade. It is deliberately not the musical recording id: the same recording
 * may legitimately appear twice in a queue, while repeat-all may materialize
 * the same logical context entry in more than one physical cycle.
 */
internal data class FailureQueueEntry(
    val key: String,
    val index: Int,
)

/**
 * Runtime-only loop guard for playback failures.
 *
 * A cascade begins with the first entry that fails before becoming audible and
 * lasts until audio starts successfully or an explicit user action resets it.
 * It never mutates the queue; it only chooses the next already-materialized
 * entry to attempt. Repeat-one is ignored for failure traversal, repeat-all may
 * wrap once, and every logical entry is attempted at most once per cascade.
 */
internal class PlaybackFailureCascade {
    sealed interface Decision {
        data class Advance(val mediaItemIndex: Int) : Decision
        data object Exhausted : Decision
    }

    private val failed = LinkedHashSet<String>()

    val isActive: Boolean
        get() = failed.isNotEmpty()

    val failedKeys: Set<String>
        get() = failed.toSet()

    fun onFailure(
        current: FailureQueueEntry,
        orderedQueue: List<FailureQueueEntry>,
        repeatMode: FailureRepeatMode,
    ): Decision {
        failed += current.key
        val currentPosition = orderedQueue.indexOfFirst {
            it.index == current.index && it.key == current.key
        }
        if (currentPosition < 0) return Decision.Exhausted

        val after = orderedQueue.drop(currentPosition + 1)
        val wrapped = if (repeatMode == FailureRepeatMode.ALL) {
            orderedQueue.take(currentPosition)
        } else {
            emptyList()
        }
        val next = (after + wrapped).firstOrNull { it.key !in failed }
            ?: return Decision.Exhausted
        return Decision.Advance(next.index)
    }

    fun onPlaybackStarted() = reset()

    fun onExplicitUserIntent() = reset()

    fun reset() {
        failed.clear()
    }
}
