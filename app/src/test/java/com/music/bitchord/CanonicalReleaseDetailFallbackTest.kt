package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.BoundedMusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogTrack
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseJsonCompleteness
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.ui.canonicalPersistedReleaseDetailPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CanonicalReleaseDetailFallbackTest {
    @Test fun rejectedPartialRefreshDisplaysTrustedCachedRowsAndNeverPublishesPartialInput() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val trackA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val trackB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            val recordingA = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            val recordingB = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
            val incompleteClient = MusicBrainzClient(object : MusicBrainzTransport, BoundedMusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String) = "<metadata/>"
                override suspend fun getLimited(path: String, parameters: Map<String, String>, userAgent: String,
                    maxResponseBytes: Long) = "<metadata/>"
            }, rateLimiter = MusicBrainzRateLimiter(delayMillis = {}))
            val index = MusicBrainzCatalogIndex(db, proofClient = incompleteClient)
            fun detail(rows: List<MusicBrainzCatalogTrack>) = MusicBrainzCatalogReleaseDetail(
                release, null, "Edition", "Artist", null, null, rows,
                completeness = MusicBrainzReleaseJsonCompleteness(true, true, true),
            )
            index.persistReleaseDetail(detail(listOf(
                MusicBrainzCatalogTrack(trackA, recordingA, "A", 1, 1, null),
                MusicBrainzCatalogTrack(trackB, recordingB, "B", 1, 2, null),
            )))

            val page = canonicalPersistedReleaseDetailPage(
                db, index, detail(listOf(MusicBrainzCatalogTrack(trackA, recordingA, "A changed", 1, 1, null))),
                title = "Edition", summary = "fresh", artworkUri = null,
            )

            assertEquals(listOf(trackA, trackB), page!!.rows.map { it.trackMbid })
            assertEquals(listOf("A", "B"), page.rows.map { it.title })
            assertEquals(listOf(false, false), page.rows.map { it.hasCatalogedAsset })
            assertEquals(listOf(true, true), page.rows.map { it.fromOfflineCache })
            val emptyDb = Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
            ).allowMainThreadQueries().build()
            try {
                val emptyPage = canonicalPersistedReleaseDetailPage(
                    emptyDb, MusicBrainzCatalogIndex(emptyDb, proofClient = incompleteClient),
                    detail(listOf(MusicBrainzCatalogTrack(trackA, recordingA, "A", 1, 1, null)))
                        .copy(musicBrainzId = "invalid"),
                    "Edition", null, null,
                )
                assertNull(emptyPage)
            } finally { emptyDb.close() }
        } finally { db.close() }
    }
}
