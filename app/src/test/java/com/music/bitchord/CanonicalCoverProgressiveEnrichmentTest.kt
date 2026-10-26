package com.music.bitchord

import com.music.bitchord.ui.loadCanonicalCoversProgressively
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CanonicalCoverProgressiveEnrichmentTest {
    @Test fun slowArchiveLookupDoesNotHoldBackAnAlreadyVerifiedCover() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val fastShown = CompletableDeferred<Unit>()
        val published = mutableMapOf<String, String>()
        val worker = launch {
            loadCanonicalCoversProgressively(listOf("slow", "fast"), maxConcurrent = 2,
                fetch = { key ->
                    if (key == "slow") {
                        slowStarted.complete(Unit)
                        releaseSlow.await()
                    }
                    "https://archive.org/$key.jpg"
                },
                publish = { ready ->
                    published.putAll(ready)
                    if ("fast" in published) fastShown.complete(Unit)
                },
            )
        }
        withTimeout(5000L) { slowStarted.await(); fastShown.await() }
        assertFalse("A stalled archive must not block the first painted cover", worker.isCompleted)
        assertEquals("https://archive.org/fast.jpg", published["fast"])
        releaseSlow.complete(Unit)
        withTimeout(5000L) { worker.join() }
        assertEquals(setOf("slow", "fast"), published.keys)
    }

    @Test fun missingArtIsNotPublishedAndDoesNotBlockOtherCompletions() = runBlocking {
        val published = mutableMapOf<String, String>()
        loadCanonicalCoversProgressively(listOf("missing", "available"), maxConcurrent = 1,
            fetch = { key -> if (key == "missing") null else "verified-art" },
            publish = { published.putAll(it) },
        )
        assertEquals(mapOf("available" to "verified-art"), published)
    }
}
