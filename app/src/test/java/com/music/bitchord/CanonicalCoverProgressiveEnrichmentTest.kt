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

    @Test fun largeArtistUsesBoundedOfficialEditionPagesInsteadOfPerGroupLookups() = runBlocking {
        val first = com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage(
            items = listOf(com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit(
                "edition1", "Get Up", "XW", "2023-07-21", "NewJeans",
                status = "Official", releaseGroupMbid = "group1",
            )),
            total = 250, offset = 0, consumedCount = 100,
        )
        val requests = mutableListOf<Int>()
        val published = mutableListOf<Map<String,
            List<com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit>>>()
        com.music.bitchord.ui.collectCanonicalArtistOfficialEditionPages(
            firstPage = first,
            maxAdditionalPages = 3,
            fetch = { offset ->
                requests += offset
                com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult.Success(
                    com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage(
                        items = listOf(
                            com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit(
                                "edition" + offset, "How Sweet", "XW", "2024-05-24",
                                "NewJeans", status = "Official",
                                releaseGroupMbid = "group" + offset,
                            ),
                            com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit(
                                "bootleg" + offset, "How Sweet", "XW", null,
                                "NewJeans", status = "Bootleg",
                                releaseGroupMbid = "bootleg-group",
                            ),
                        ),
                        total = 250, offset = offset,
                        consumedCount = if (offset == 100) 100 else 50,
                    ),
                )
            },
            onPage = { published += it },
        )
        assertEquals(listOf(100, 200), requests)
        assertEquals(listOf("group1", "group100", "group200"),
            published.last().keys.toList())
        assertFalse(published.any { "bootleg-group" in it })
    }

}
