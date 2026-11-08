package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import org.junit.Assert.*
import org.junit.Test

class DiscogsSourceOnlyShelfStoreTest {
    private fun release(id: Long, title: String) = DiscogsShadowRelease(
        key = "discogs:release:$id",
        discogsReleaseId = id,
        title = title, artist = "ILLIT", year = 2026,
        kind = DiscogsEditorialKind.EP, shelf = DiscogsEditorialShelf.EPS,
        attributionUrl = "https://www.discogs.com/release/$id",
    )

    @Test fun onlyVerifiedSourceOnlySnapshotsCanBeOpenedThroughExactDiscogsIds() {
        val store = DiscogsSourceOnlyShelfStore()
        val first = release(101, "MISSING EP")
        store.update("00000000-0000-4000-8000-000000000010",
            listOf(first), nowMillis = 1_000)
        assertEquals(listOf(first), store.active(1_001))
        assertEquals(first.attributionUrl, store.openUrl("discogs:release:101", 1_001))
        assertNull(store.openUrl("catalog:RELEASE_GROUP:101", 1_001))
        assertNull(store.openUrl("discogs:release:102", 1_001))
        assertTrue(store.active(1_000 + DiscogsObservation.TTL_MILLIS).isEmpty())
    }

    @Test fun rejectsMalformedAttributionAndDoesNotDuplicateArtistSnapshots() {
        val store = DiscogsSourceOnlyShelfStore()
        val bad = release(21, "Bad").copy(attributionUrl = "https://evil.example/release/21")
        store.update("00000000-0000-4000-8000-000000000010", listOf(bad),
            nowMillis = 10)
        assertTrue(store.active(11).isEmpty())
        val first = release(21, "Good")
        store.update("00000000-0000-4000-8000-000000000010", listOf(first),
            nowMillis = 11)
        store.update("00000000-0000-4000-8000-000000000010", listOf(first),
            nowMillis = 12)
        assertEquals(1, store.active(13).size)
        store.clear()
        assertTrue(store.active(13).isEmpty())
    }
}
