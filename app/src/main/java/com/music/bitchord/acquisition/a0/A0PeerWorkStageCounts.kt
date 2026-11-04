package com.music.bitchord.acquisition.a0

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Aggregate-only lifecycle counters for indirect Soulseek P worker teardown.
 * Tracks live stages, never source addresses, peer names or remote paths.
 */
internal class A0PeerWorkStageCounts {
    enum class Stage { CONNECTING, READING, PUBLISHING }
    private val counts=AtomicIntegerArray(Stage.entries.size)

    fun <T> inStage(stage:Stage,work:()->T):T {
        val index=stage.ordinal
        counts.incrementAndGet(index)
        return try { work() } finally { counts.decrementAndGet(index) }
    }

    fun summary():String=
        "connecting=${counts.get(Stage.CONNECTING.ordinal).coerceAtLeast(0)}" +
        " reading=${counts.get(Stage.READING.ordinal).coerceAtLeast(0)}" +
        " publishing=${counts.get(Stage.PUBLISHING.ordinal).coerceAtLeast(0)}"
}
