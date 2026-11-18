package com.music.bitchord

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderReactiveCache
import com.music.bitchord.data.providers.ProviderSessionSnapshot
import com.music.bitchord.data.providers.ProviderSessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generic policy-driven cache visibility without any screen or UI dependency. */
class ProviderReactiveCacheTest {
    private val mb = ProviderKey("musicbrainz")
    private val fanart = ProviderKey("fanarttv")

    private fun state(vararg eligible: Pair<ProviderCapability, List<ProviderKey>>) =
        ProviderSessionSnapshot(
            status = ProviderSessionStatus.READY,
            eligible = eligible.toMap(),
        )

    @Test fun disabledProviderImmediatelyHidesCachedResultsWithoutRestartOrDeletingCache(): Unit = runBlocking {
        val snapshots = MutableStateFlow(state(
            ProviderCapability.CATALOG_SEARCH to listOf(mb),
        ))
        var reads = 0
        val results = Channel<String>(Channel.UNLIMITED)
        val collect = launch {
            ProviderReactiveCache(snapshots).observe(
                ProviderCapability.CATALOG_SEARCH, mb, "disabled",
            ) {
                reads++
                "cached response"
            }.collect { results.send(it) }
        }
        try {
            assertEquals("cached response", withTimeout(3_000) { results.receive() })
            assertEquals(1, reads)
            snapshots.value = state(
                ProviderCapability.CATALOG_SEARCH to listOf(mb),
                ProviderCapability.RELEASE_ARTWORK to listOf(fanart),
            )
            yield()
            assertEquals(1, reads) // unrelated artwork preference does no SQL
            snapshots.value = state(ProviderCapability.RELEASE_ARTWORK to listOf(fanart))
            assertEquals("disabled", withTimeout(3_000) { results.receive() })
            assertEquals(1, reads)
            snapshots.value = state(
                ProviderCapability.CATALOG_SEARCH to listOf(mb),
                ProviderCapability.RELEASE_ARTWORK to listOf(fanart),
            )
            assertEquals("cached response", withTimeout(3_000) { results.receive() })
            assertEquals(2, reads)
        } finally {
            collect.cancelAndJoin()
        }
    }

    @Test fun revocationCancelsSlowCacheReadAndEmitsEmptyWithoutOldSnapshot(): Unit = runBlocking {
        val snapshots = MutableStateFlow(state())
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val results = Channel<String>(Channel.UNLIMITED)
        var cancelled = false
        val collect = launch {
            ProviderReactiveCache(snapshots).observe(
                ProviderCapability.CATALOG_SEARCH, mb, "disabled",
            ) {
                entered.complete(Unit)
                try {
                    unblock.await()
                    "obsolete"
                } finally {
                    cancelled = true
                }
            }.collect { results.send(it) }
        }
        try {
            assertEquals("disabled", withTimeout(3_000) { results.receive() })
            snapshots.value = state(ProviderCapability.CATALOG_SEARCH to listOf(mb))
            withTimeout(3_000) { entered.await() }
            snapshots.value = state()
            assertEquals("disabled", withTimeout(3_000) { results.receive() })
            assertTrue(cancelled)
        } finally {
            unblock.complete(Unit)
            collect.cancelAndJoin()
        }
    }
}
