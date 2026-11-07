package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistAppearance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeArtistIncludedInArtworkTest {
    private val firstGroup = "11111111-1111-4111-8111-111111111111"
    private val secondGroup = "22222222-2222-4222-8222-222222222222"
    private val firstEdition = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val secondEdition = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test fun verifiedGroupCoverIsReusedWithoutReopeningTheReleaseFront() = runBlocking {
        var releaseFrontRequests = 0
        val art = nativeArtistIncludedInCoverFromVerifiedCache(
            releaseMbid = firstEdition,
            groupMbid = firstGroup,
            cache = { "https://archive.org/verified-sleeve.jpg" },
            fetch = { _, _ -> releaseFrontRequests++; null },
        )
        assertEquals("https://archive.org/verified-sleeve.jpg", art)
        assertEquals(0, releaseFrontRequests)
    }

    @Test fun slowFirstAppearanceCoverDoesNotBlockSecondVerifiedGroup() = runBlocking {
        val slowCover = CompletableDeferred<Unit>()
        val secondCoverVisible = CompletableDeferred<Unit>()
        val seen = mutableMapOf<String, String>()
        val appearances = listOf(
            MusicBrainzArtistAppearance(firstGroup, "First", "Artist A",
                "2025-01-01", "Album", firstEdition),
            MusicBrainzArtistAppearance(secondGroup, "Second", "Artist B",
                "2026-01-01", "Single", secondEdition),
        )
        val job = async {
            resolveNativeIncludedInCovers(
                appearances,
                lookup = { edition, group ->
                    if (group == firstGroup) slowCover.await()
                    "https://coverartarchive.org/release/" + edition + "/front-250"
                },
                onResolved = { batch ->
                    seen.putAll(batch)
                    if (secondGroup in seen) secondCoverVisible.complete(Unit)
                },
            )
        }
        try {
            withTimeout(3_000L) { secondCoverVisible.await() }
            assertTrue(firstGroup !in seen)
            assertEquals("https://coverartarchive.org/release/" + secondEdition + "/front-250",
                seen[secondGroup])
        } finally {
            slowCover.complete(Unit)
        }
        job.await()
        assertEquals(setOf(firstGroup, secondGroup), seen.keys)
    }

    @Test fun sameGroupIsOnlyFetchedOnceAndRetainsItsVerifiedEdition() = runBlocking {
        val group = MusicBrainzArtistAppearance(firstGroup, "Album", "Artist A",
            null, "Album", firstEdition)
        var requests = 0
        var resolved = emptyMap<String, String>()
        resolveNativeIncludedInCovers(
            listOf(group, group.copy(releaseMbid = secondEdition)),
            lookup = { edition, _ ->
                requests++
                edition
            },
            onResolved = { resolved = resolved + it },
        )
        assertEquals(1, requests)
        assertEquals(mapOf(firstGroup to firstEdition), resolved)
    }
}
