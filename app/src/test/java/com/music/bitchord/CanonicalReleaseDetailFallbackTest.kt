package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.BoundedMusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogTrack
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseJsonCompleteness
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.ui.canonicalPersistedReleaseDetailPage
import com.music.bitchord.ui.canonicalVerifiedLiveReleaseDetailPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CanonicalReleaseDetailFallbackTest {
    @Test fun catalogIndexPreservesPositionalNamedAndTrailingIdFactoryForms() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val proofClient = MusicBrainzClient(object : MusicBrainzTransport, BoundedMusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String) = "<metadata/>"
                override suspend fun getLimited(
                    path: String,
                    parameters: Map<String, String>,
                    userAgent: String,
                    maxResponseBytes: Long,
                ) = "<metadata/>"
            })
            val factoryVariable: () -> String = { "variable-factory" }
            suspend fun storedId(index: MusicBrainzCatalogIndex, mbid: String) =
                index.persistRecordings(listOf(MusicBrainzRecordingHit(mbid, "Track", "Artist", null)))
                    .getValue(mbid)

            assertEquals("positional", storedId(
                MusicBrainzCatalogIndex(db, { "positional" }),
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            ))
            assertEquals("variable-factory", storedId(
                MusicBrainzCatalogIndex(db, factoryVariable),
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            ))
            assertEquals("trailing", storedId(
                MusicBrainzCatalogIndex(db) { "trailing" },
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            ))
            assertEquals("named", storedId(
                MusicBrainzCatalogIndex(db, idFactory = { "named" }),
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            ))
            assertEquals("proof-trailing", storedId(
                MusicBrainzCatalogIndex(db, proofClient = proofClient) { "proof-trailing" },
                "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            ))
            assertEquals("proof-named", storedId(
                MusicBrainzCatalogIndex(db, proofClient = proofClient, idFactory = { "proof-named" }),
                "ffffffff-ffff-4fff-8fff-ffffffffffff",
            ))
            assertEquals(1, MusicBrainzCatalogIndex(db).persistRecordings(listOf(
                MusicBrainzRecordingHit("11111111-1111-4111-8111-111111111111", "Default", "Artist", null),
            )).size)
        } finally {
            db.close()
        }
    }

    @Test fun catalogIndexKeepsTrailingIdFactoryWithNamedProofClientInjection() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            var generated = 0
            val proofClient = MusicBrainzClient(object : MusicBrainzTransport, BoundedMusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String) = "<metadata/>"
                override suspend fun getLimited(
                    path: String,
                    parameters: Map<String, String>,
                    userAgent: String,
                    maxResponseBytes: Long,
                ) = "<metadata/>"
            })
            val index = MusicBrainzCatalogIndex(db, proofClient = proofClient) {
                "compat-recording-${++generated}"
            }
            val hits = listOf(
                MusicBrainzRecordingHit("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Track", "Artist", null),
            )

            val indexed = index.persistRecordings(hits)

            assertEquals("compat-recording-1", indexed.getValue(hits.single().musicBrainzId))
        } finally {
            db.close()
        }
    }

    @Test fun completeLiveMusicBrainzEditionStillDisplaysWithoutRoomPersistence() {
        val releaseId = "74c530a8-9280-48bc-88f5-635c1cfb6173"
        val good = MusicBrainzCatalogReleaseDetail(
            releaseId, null, "&Our Vibe", "SAY MY NAME", null, "2025-12-29",
            listOf(
                MusicBrainzCatalogTrack(
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    "Bad Idea", 1, 1, null,
                ),
                MusicBrainzCatalogTrack(
                    "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                    "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    "UFO (ATTENT!ON)", 1, 2, null,
                ),
            ),
            completeness = MusicBrainzReleaseJsonCompleteness(true, true, true),
        )
        val page = canonicalVerifiedLiveReleaseDetailPage(good, null)
        assertNotNull(page)
        assertEquals(listOf("1.1  Bad Idea", "1.2  UFO (ATTENT!ON)"),
            page!!.rows.map { it.title })
        assertEquals(listOf(false, false), page.rows.map { it.hasCatalogedAsset })
        assertNull(canonicalVerifiedLiveReleaseDetailPage(
            good.copy(completeness = MusicBrainzReleaseJsonCompleteness(false, true, true)),
            null,
        ))
        assertNull(canonicalVerifiedLiveReleaseDetailPage(
            good.copy(tracks = emptyList()), null,
        ))
    }

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
