package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsArtistGroupPage
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsShadowCatalog
import com.music.bitchord.data.catalog.metadata.discogs.collectCompleteDiscogsArtistGroupTitles
import com.music.bitchord.data.catalog.metadata.discogs.collectCompleteDiscogsArtistReleaseTitles
import com.music.bitchord.data.catalog.metadata.discogs.completeDiscogsArtistKnownTitles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Never declare a Discogs-only release absent from a partially read MB catalog. */
class DiscogsArtistCatalogCompletionTest {
    private fun group(id: Int, title: String): Pair<String, String> =
        "00000000-0000-4000-8000-%012d".format(id) to title

    @Test fun completesTwoPagesByServerCursorBeforeAllowingShadowReleases() = runBlocking {
        val offsets = mutableListOf<Int>()
        val actual = collectCompleteDiscogsArtistGroupTitles(pageSize = 2, maxGroups = 4) { offset ->
            offsets += offset
            when (offset) {
                0 -> DiscogsArtistGroupPage(
                    groups = listOf(group(1, "Album"), group(2, "EP")),
                    total = 3, offset = 0, consumedCount = 2,
                )
                2 -> DiscogsArtistGroupPage(
                    groups = listOf(group(3, "Single")),
                    total = 3, offset = 2, consumedCount = 1,
                )
                else -> error("Unexpected page")
            }
        }
        assertEquals(setOf("Album", "EP", "Single"), actual)
        assertEquals(listOf(0, 2), offsets)
    }

    @Test fun repackageTitleMustBeRecognizedInsideACompletedReleaseBrowse() = runBlocking {
        val visited = mutableListOf<Int>()
        val canonicalGroup = setOf("ROMANCE : UNTOLD")
        val exactReleases = collectCompleteDiscogsArtistReleaseTitles { offset ->
            visited += offset
            when (offset) {
                0 -> DiscogsArtistGroupPage(
                    groups = (1..100).map { n -> group(n, "Album edition $n") },
                    total = 101, offset = 0, consumedCount = 100,
                )
                100 -> DiscogsArtistGroupPage(
                    groups = listOf(group(101, "ROMANCE : UNTOLD -daydream-")),
                    total = 101, offset = 100, consumedCount = 1,
                )
                else -> error("No third network request")
            }
        }
        assertEquals(listOf(0,100),visited)
        assertEquals(true, exactReleases?.contains("ROMANCE : UNTOLD -daydream-"))
        assertEquals(true, completeDiscogsArtistKnownTitles(
            canonicalGroup, exactReleases
        )?.contains("ROMANCE : UNTOLD -daydream-"))
        assertEquals(true, completeDiscogsArtistKnownTitles(
            canonicalGroup, exactReleases
        )?.contains("ROMANCE : UNTOLD"))
    }

    @Test fun knownReleaseTitleSuppressesDaydreamButNotDistinctRemix() {
        val known = completeDiscogsArtistKnownTitles(
            setOf("ROMANCE : UNTOLD"),
            setOf("ROMANCE : UNTOLD -daydream-"),
        ) ?: error("Complete MB title set")
        val matcher = DiscogsShadowCatalog.knownTitlePredicate(
            "ENHYPEN", known,
        )
        assertTrue(matcher("Romance: Untold -Daydream-"))
        assertFalse(matcher("Romance: Untold -Daydream- (Live)"))
        assertFalse(matcher("Romance: Untold -Daydream- / Acoustic"))
    }

    @Test fun incompleteReleaseBrowseFailsClosedWithoutRenderingSourceOnly() = runBlocking {
        val partial = collectCompleteDiscogsArtistReleaseTitles { offset ->
            DiscogsArtistGroupPage(listOf(group(1, "Title Only")),
                total = 301, offset = offset, consumedCount = 1)
        }
        assertNull(partial)
        assertNull(completeDiscogsArtistKnownTitles(
            setOf("Existing Group"), partial))
    }

    @Test fun completeEmptyArtistCanStillHaveDiscogsOnlyMetadata() = runBlocking {
        assertEquals(emptySet<String>(),
            collectCompleteDiscogsArtistGroupTitles { offset ->
                DiscogsArtistGroupPage(emptyList(), total = 0, offset = offset, consumedCount = 0)
            })
    }

    @Test fun neverClaimsCompletenessFromIncompleteOrContradictoryPages() = runBlocking {
        assertNull(collectCompleteDiscogsArtistGroupTitles(pageSize = 2, maxGroups = 4) { offset ->
            DiscogsArtistGroupPage(listOf(group(1, "Only One")),
                total = 3, offset = offset, consumedCount = 0)
        })
        assertNull(collectCompleteDiscogsArtistGroupTitles(pageSize = 2, maxGroups = 4) { offset ->
            DiscogsArtistGroupPage(listOf(group(1, "Only One"), group(2, "Another")),
                total = 3, offset = offset, consumedCount = 2)
        })
        assertNull(collectCompleteDiscogsArtistGroupTitles(maxGroups = 2) { offset ->
            DiscogsArtistGroupPage(emptyList(), total = 3, offset = offset, consumedCount = 0)
        })
    }

    @Test fun discardsDuplicateGroupIdentityAcrossPagesRatherThanClaimingAbsent() = runBlocking {
        val result = collectCompleteDiscogsArtistGroupTitles(pageSize = 1, maxGroups = 3) { offset ->
            DiscogsArtistGroupPage(listOf(group(1, "Repeated")),
                total = 2, offset = offset, consumedCount = 1)
        }
        assertNull(result)
    }
}
