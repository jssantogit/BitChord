package com.music.bitchord.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Deliver verified covers as soon as each independent lookup finishes.
 *
 * Waiting for the entire CAA wave previously made a quick 500px cover wait
 * behind the slowest metadata request (p95 on device was ~5.8 seconds).
 * Drain already-ready completions into one immutable batch to avoid a
 * separate recomposition for responses arriving in the same scheduler turn.
 * Cancellation still cancels every child; missing art never emits a row.
 */
internal suspend fun <K, V : Any> loadCanonicalCoversProgressively(
    keys: List<K>,
    maxConcurrent: Int = 3,
    fetch: suspend (K) -> V?,
    publish: suspend (Map<K, V>) -> Unit,
) = coroutineScope {
    require(maxConcurrent > 0)
    val permits = Semaphore(maxConcurrent)
    val completions = Channel<Pair<K, V>>(Channel.UNLIMITED)
    val jobs = keys.map { key ->
        launch {
            permits.withPermit {
                fetch(key)?.let { completions.send(key to it) }
            }
        }
    }
    launch {
        jobs.joinAll()
        completions.close()
    }
    for ((key, value) in completions) {
        val batch = linkedMapOf(key to value)
        while (true) {
            val next = completions.tryReceive().getOrNull() ?: break
            batch[next.first] = next.second
        }
        publish(batch)
    }
}
