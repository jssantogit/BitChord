package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.BoundedMusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogTrack
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseJsonCompleteness
import com.music.bitchord.data.catalog.metadata.KtorMusicBrainzTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class MusicBrainzReleaseGraphIndexTest {
    // Synthetic MusicBrainz response: schema-valid zero and positive positions must round-trip.
    @Test fun zeroAndPositivePositionsRoundTripThroughRoomWithoutPartialEdition() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val mediumZeroTrack = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val mediumZeroRecording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            val trackZero = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            val trackZeroRecording = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
            val positiveTrack = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
            val positiveRecording = "ffffffff-ffff-4fff-8fff-ffffffffffff"
            val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$release","title":"Synthetic zero-position edition","media":[
                    {"position":0,"tracks":[
                        {"id":"$mediumZeroTrack","position":7,"title":"Zero medium","recording":{"id":"$mediumZeroRecording","title":"Zero medium"}}
                    ]},
                    {"position":3,"tracks":[
                        {"id":"$trackZero","position":0,"title":"Zero track","recording":{"id":"$trackZeroRecording","title":"Zero track"}}
                    ]},
                    {"position":8,"tracks":[
                        {"id":"$positiveTrack","position":13,"title":"Positive positions","recording":{"id":"$positiveRecording","title":"Positive positions"}}
                    ]}
                ]}"""
            }))

            val online = (graph.lookupRelease(release) as
                MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
            val index = MusicBrainzCatalogIndex(db)
            index.persistReleaseDetail(online)
            val cached = index.cachedRelease(release)!!

            assertEquals(
                listOf(mediumZeroTrack, trackZero, positiveTrack),
                cached.tracks.map { it.trackMbid },
            )
            assertEquals(
                listOf(0 to 7, 3 to 0, 8 to 13),
                cached.tracks.map { it.mediumNumber to it.trackNumber },
            )
            assertEquals(online.tracks.map { it.trackMbid }, cached.tracks.map { it.trackMbid })
            assertEquals(
                online.tracks.map { it.mediumNumber to it.trackNumber },
                cached.tracks.map { it.mediumNumber to it.trackNumber },
            )
        } finally { db.close() }
    }

    @Test fun largeHttpErrorBodyDuringProofPreservesCachedTrackSidecars() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        val server = MockWebServer()
        val http = HttpClient(OkHttp) { expectSuccess = true }
        server.start()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            val ra = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            val rb = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
            val index = MusicBrainzCatalogIndex(db, proofClient = MusicBrainzClient(
                transport = KtorMusicBrainzTransport(server.url("/ws/2").toString(), http),
                rateLimiter = MusicBrainzRateLimiter(delayMillis = {}),
            ))
            index.persistReleaseDetail(detail(release, listOf(a to ra, b to rb)))
            server.enqueue(MockResponse().setResponseCode(404)
                .setChunkedBody("z".repeat(2_100_000), 8_192))

            val result = index.persistReleaseDetail(detail(release, listOf(a to ra)))

            val releaseId = db.catalogDao().entityIdForExternalIdentity("musicbrainz", "release", release)!!
            assertNull(result)
            assertEquals(setOf(a, b), db.catalogDao().catalogMusicBrainzTracks(releaseId).map { it.trackMbid }.toSet())
            assertEquals(1, server.requestCount)
        } finally {
            http.close()
            server.shutdown()
            db.close()
        }
    }

    @Test fun completeXmlSnapshotAllowsOnlyObsoleteMusicBrainzTrackSidecarRemoval() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            val ra = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            val rb = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
            val xml = """
                <metadata xmlns="http://musicbrainz.org/ns/mmd-2.0#"><release id="$release"><medium-list count="1"><medium>
                <position>1</position><track-list count="1"><track id="$a"><position>1</position>
                <recording id="$ra"/></track></track-list></medium></medium-list></release></metadata>
            """.trimIndent()
            var xmlRequests = 0
            val client = MusicBrainzClient(object : MusicBrainzTransport, BoundedMusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                    xmlRequests++
                    assertEquals("release/$release", path)
                    assertEquals("xml", parameters["fmt"])
                    assertEquals("recordings", parameters["inc"])
                    return xml
                }
                override suspend fun getLimited(path: String, parameters: Map<String, String>, userAgent: String,
                    maxResponseBytes: Long): String = get(path, parameters, userAgent)
            }, rateLimiter = MusicBrainzRateLimiter(delayMillis = {}))
            val index = MusicBrainzCatalogIndex(db, proofClient = client)
            index.persistReleaseDetail(detail(release, listOf(a to ra, b to rb)))
            val dao = db.catalogDao()
            val releaseId = dao.entityIdForExternalIdentity("musicbrainz", "release", release)!!
            val recordingB = dao.entityIdForExternalIdentity("musicbrainz", "recording", rb)!!
            dao.upsertMediaAsset(MediaAssetEntity("asset-b", recordingB, "local", "b", "content://b", null, null, null, null))
            dao.upsertReleaseTrack(ReleaseTrackEntity(releaseId, recordingB, 2))
            dao.upsertCatalogReleaseTrackSource(com.music.bitchord.data.catalog.CatalogReleaseTrackSourceEntity(releaseId, recordingB, "third-party"))

            index.persistReleaseDetail(detail(release, listOf(a to ra)))

            assertEquals(listOf(a), dao.catalogMusicBrainzTracks(releaseId).map { it.trackMbid })
            assertEquals(recordingB, dao.recordingById(recordingB)!!.id)
            assertEquals("asset-b", dao.mediaAsset("local", "b")!!.id)
            assertTrue(dao.catalogReleaseTracksForRecording(recordingB).any { it.releaseId == releaseId })
            assertTrue(dao.catalogReleaseTrackSources(releaseId, recordingB).any { it.provider == "third-party" })
            assertEquals(1, xmlRequests)
        } finally { db.close() }
    }

    private fun detail(release: String, tracks: List<Pair<String, String>>) =
        MusicBrainzCatalogReleaseDetail(release, null, "Edition", null, null, null,
            tracks.mapIndexed { i, (track, recording) -> MusicBrainzCatalogTrack(track, recording, "T$i", 1, i + 1, null) },
            completeness = MusicBrainzReleaseJsonCompleteness(true, true, true))

    @Test fun additiveUpdateNeverFetchesXmlAndUnprovenRemovalPreservesSidecar() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            val ra = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            val rb = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
            var xmlCalls = 0
            val client = MusicBrainzClient(object : MusicBrainzTransport, BoundedMusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                    xmlCalls++
                    error("offline")
                }
                override suspend fun getLimited(path: String, parameters: Map<String, String>, userAgent: String,
                    maxResponseBytes: Long): String = get(path, parameters, userAgent)
            }, rateLimiter = MusicBrainzRateLimiter(delayMillis = {}))
            val index = MusicBrainzCatalogIndex(db, proofClient = client)
            index.persistReleaseDetail(detail(release, listOf(a to ra)))
            index.persistReleaseDetail(detail(release, listOf(a to ra, b to rb)))
            assertEquals(0, xmlCalls)
            index.persistReleaseDetail(detail(release, listOf(a to ra)))
            val releaseId = db.catalogDao().entityIdForExternalIdentity("musicbrainz", "release", release)!!
            assertEquals(setOf(a, b), db.catalogDao().catalogMusicBrainzTracks(releaseId).map { it.trackMbid }.toSet())
            assertEquals(1, xmlCalls)
            index.persistReleaseDetail(detail(release, emptyList()))
            assertEquals(setOf(a, b), db.catalogDao().catalogMusicBrainzTracks(releaseId).map { it.trackMbid }.toSet())
            assertEquals(1, xmlCalls)
        } finally { db.close() }
    }

    @Test fun sidecarRaceDuringXmlProofAbortsReconciliation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            val ra = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            val rb = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
            lateinit var index: MusicBrainzCatalogIndex
            val xml = """<metadata xmlns="http://musicbrainz.org/ns/mmd-2.0#"><release id="$release"><medium-list count="1"><medium><position>1</position><track-list count="1"><track id="$a"><position>1</position><recording id="$ra"/></track></track-list></medium></medium-list></release></metadata>"""
            val client = MusicBrainzClient(object : MusicBrainzTransport, BoundedMusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                    val dao = db.catalogDao()
                    val row = dao.catalogMusicBrainzTrack(b)!!
                    dao.upsertCatalogMusicBrainzTrack(row.copy(title = "concurrent edit"))
                    return xml
                }
                override suspend fun getLimited(path: String, parameters: Map<String, String>, userAgent: String,
                    maxResponseBytes: Long): String = get(path, parameters, userAgent)
            }, rateLimiter = MusicBrainzRateLimiter(delayMillis = {}))
            index = MusicBrainzCatalogIndex(db, proofClient = client)
            index.persistReleaseDetail(detail(release, listOf(a to ra, b to rb)))

            val result = index.persistReleaseDetail(detail(release, listOf(a to ra)))

            val releaseId = db.catalogDao().entityIdForExternalIdentity("musicbrainz", "release", release)!!
            assertNull(result)
            assertEquals(setOf(a, b), db.catalogDao().catalogMusicBrainzTracks(releaseId).map { it.trackMbid }.toSet())
            assertEquals("concurrent edit", db.catalogDao().catalogMusicBrainzTrack(b)!!.title)
        } finally { db.close() }
    }
    @Test fun sameEditionTrackRecordingCorrectionPreservesOldRecordingOwnershipAndRejectsEditionReuse() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val otherRelease = "271b31f8-8ab6-483c-97d9-8f0fabb2d4f4"
            val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val recording1 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            val recording2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
            val index = MusicBrainzCatalogIndex(db)
            val dao = db.catalogDao()
            index.persistReleaseDetail(MusicBrainzCatalogReleaseDetail(
                release, null, "Edition", null, null, null,
                listOf(MusicBrainzCatalogTrack(track, recording1, "Track", 1, 1, null)),
            ))
            val releaseId = dao.entityIdForExternalIdentity("musicbrainz", "release", release)!!
            val oldRecordingId = dao.entityIdForExternalIdentity("musicbrainz", "recording", recording1)!!
            val newRecordingId = index.persistRecordings(listOf(
                com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit(
                    recording2, "Corrected", "Artist", null,
                ),
            ))[recording2]!!
            dao.upsertMediaAsset(MediaAssetEntity(
                "existing-asset", oldRecordingId, "local", "source", "content://asset",
                null, null, null, null,
            ))
            dao.upsertExternalIdentity(ExternalIdentityEntity("other-provider", "recording", "old-claim", oldRecordingId))
            dao.upsertReleaseTrack(ReleaseTrackEntity(releaseId, oldRecordingId, 1))
            dao.upsertCatalogReleaseTrackSource(com.music.bitchord.data.catalog.CatalogReleaseTrackSourceEntity(
                releaseId, oldRecordingId, "other-provider",
            ))

            index.persistReleaseDetail(MusicBrainzCatalogReleaseDetail(
                release, null, "Edition", null, null, null,
                listOf(MusicBrainzCatalogTrack(track, recording2, "Corrected", 1, 1, null)),
            ))

            assertEquals(newRecordingId, dao.catalogMusicBrainzTrack(track)!!.recordingId)
            assertEquals(recording2, index.cachedRelease(release)!!.tracks.single().recordingMbid)
            assertEquals(oldRecordingId, dao.recordingById(oldRecordingId)!!.id)
            assertEquals("existing-asset", dao.mediaAsset("local", "source")!!.id)
            assertEquals(oldRecordingId, dao.entityIdForExternalIdentity("other-provider", "recording", "old-claim"))
            val preservedSources = dao.catalogReleaseTrackSources(releaseId, oldRecordingId).map { it.provider }.toSet()
            assertTrue("musicbrainz" in preservedSources)
            assertTrue("other-provider" in preservedSources)
            // Keep the old R1 edge alongside the new R2 edge. Without complete
            // provenance, pruning legacy ownership edges would be unsafe.
            assertTrue(dao.catalogReleaseTrackSources(releaseId, oldRecordingId).isNotEmpty())
            assertTrue(dao.catalogReleaseTrackSources(releaseId, newRecordingId).any {
                it.provider == "musicbrainz"
            })

            val failedReuse = runCatching {
                index.persistReleaseDetail(MusicBrainzCatalogReleaseDetail(
                    otherRelease, null, "Other edition", null, null, null,
                    listOf(MusicBrainzCatalogTrack(track, recording2, "Corrected", 1, 1, null)),
                ))
            }
            assertTrue(failedReuse.isFailure)
            // The rejected correction rolls back its release identity and Track association;
            // pre-existing Recording 2 remains untouched.
            assertNull(dao.entityIdForExternalIdentity("musicbrainz", "release", otherRelease))
            assertEquals(releaseId, dao.catalogMusicBrainzTrack(track)!!.releaseId)

            val sharedRecordingTrack = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
            index.persistReleaseDetail(MusicBrainzCatalogReleaseDetail(
                otherRelease, null, "Other edition", null, null, null,
                listOf(MusicBrainzCatalogTrack(sharedRecordingTrack, recording2, "Another track", 1, 2, null)),
            ))
            assertEquals(newRecordingId, dao.catalogMusicBrainzTrack(sharedRecordingTrack)!!.recordingId)
        } finally { db.close() }
    }

    @Test fun malformedRefreshCannotAddPartialTracksToPreviouslyCachedEdition() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val cachedTrack = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val cachedRecording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            val partialTrack = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            val partialRecording = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
            val index = MusicBrainzCatalogIndex(db)
            index.persistReleaseDetail(MusicBrainzCatalogReleaseDetail(
                release, null, "Known cached edition", null, null, null,
                listOf(MusicBrainzCatalogTrack(
                    cachedTrack, cachedRecording, "Cached track", 1, 5, null,
                )),
            ))
            val cachedBefore = index.cachedRelease(release)!!.tracks
            val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$release","title":"Synthetic incomplete refresh","media":[
                    {"position":1,"tracks":[
                        {"id":"$partialTrack","position":1,"title":"Partial track","recording":{"id":"$partialRecording"}}
                    ]},
                    {"position":2,"tracks":"not-an-array"}
                ]}"""
            }))

            val refresh = graph.lookupRelease(release)
            if (refresh is MusicBrainzCatalogGraphResult.Success) {
                index.persistReleaseDetail(refresh.value)
            }

            assertEquals(cachedBefore, index.cachedRelease(release)!!.tracks)
            assertTrue(refresh is MusicBrainzCatalogGraphResult.Failure)
        } finally { db.close() }
    }

    // Synthetic MusicBrainz-shaped response, checked against the persisted cache ordering.
    @Test fun onlineTrackOrderMatchesCachedOrderAndPreservesMissingPositionFallbacks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val trackFallbackMedium = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val trackFallbackMediumRecording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            val explicitLaterTrack = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            val explicitLaterRecording = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
            val trackFallback = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
            val trackFallbackRecording = "ffffffff-ffff-4fff-8fff-ffffffffffff"
            val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$release","title":"Reordered edition","media":[
                    {"position":3,"tracks":[
                        {"id":"$explicitLaterTrack","position":10,"title":"Later position","recording":{"id":"$explicitLaterRecording"}},
                        {"id":"$trackFallback","title":"Fallback position","recording":{"id":"$trackFallbackRecording"}}
                    ]},
                    {"tracks":[
                        {"id":"$trackFallbackMedium","title":"Fallback medium and track","recording":{"id":"$trackFallbackMediumRecording"}}
                    ]}
                ]}"""
            }))

            val online = (graph.lookupRelease(release) as
                MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
            val index = MusicBrainzCatalogIndex(db)
            index.persistReleaseDetail(online)
            val cached = index.cachedRelease(release)!!

            assertEquals(listOf(2, 3, 3), online.tracks.map { it.mediumNumber })
            assertEquals(listOf(1, 2, 10), online.tracks.map { it.trackNumber })
            assertEquals(online.tracks.map { it.trackMbid }, cached.tracks.map { it.trackMbid })
            assertEquals(online.tracks.map { it.mediumNumber to it.trackNumber },
                cached.tracks.map { it.mediumNumber to it.trackNumber })
        } finally { db.close() }
    }

    @Test fun recordingMetadataComesFromNestedRecordingWhileTrackKeepsEditionFields() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val recording = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val track = "14f273b6-813b-4239-b0f8-c4b839b902c6"
            val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String,
                    parameters: Map<String, String>,
                    userAgent: String,
                ): String = """{"id":"$release","title":"SUPER REAL ME","media":[{"position":1,"tracks":[
                    {"id":"$track","position":2,"title":"Magnetic","length":160000,
                     "recording":{"id":"$recording","title":"Magnetic (sped up)","length":124000,
                     "artist-credit":[{"name":"ILLIT"}]}}
                ]}]}"""
            }))
            val detail = (graph.lookupRelease(release) as
                MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
            assertEquals("Magnetic", detail.tracks.single().title)
            assertEquals(160_000L, detail.tracks.single().durationMs)

            val index = MusicBrainzCatalogIndex(db)
            index.persistReleaseDetail(detail)
            val recordingId = db.catalogDao().entityIdForExternalIdentity(
                "musicbrainz", "recording", recording,
            )!!
            val cachedRecording = db.catalogDao().recordingById(recordingId)!!

            assertEquals("Magnetic (sped up)", cachedRecording.title)
            assertEquals(124_000L, cachedRecording.durationMs)
            assertEquals("Magnetic", db.catalogDao().catalogMusicBrainzTracks(
                db.catalogDao().entityIdForExternalIdentity("musicbrainz", "release", release)!!,
            ).single().title)
            val cachedEdition = index.cachedRelease(release)!!
            assertEquals("Magnetic", cachedEdition.tracks.single().title)
            assertEquals(160_000L, cachedEdition.tracks.single().durationMs)
            assertEquals("Magnetic (sped up)", cachedEdition.tracks.single().recordingTitle)
            assertEquals(124_000L, cachedEdition.tracks.single().recordingDurationMs)
        } finally { db.close() }
    }

    @Test fun cachedRecordingEvidenceDoesNotOverwriteExistingCanonicalLocalMetadata() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val recording = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val index = MusicBrainzCatalogIndex(db)
            val id = index.persistRecordings(listOf(
                com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit(
                    recording, "Preserved local title", "Local credits", 99_000L,
                ),
            ))[recording]!!
            val detail = MusicBrainzCatalogReleaseDetail(release, null, "SUPER REAL ME", "ILLIT",
                "XW", "2024-03-25", listOf(
                    MusicBrainzCatalogTrack(
                        trackMbid = "14f273b6-813b-4239-b0f8-c4b839b902c6",
                        recordingMbid = recording,
                        title = "Magnetic (edition)",
                        mediumNumber = 1, trackNumber = 2, durationMs = 160_000L,
                        recordingTitle = "Magnetic (sped up)",
                        recordingDurationMs = 123_000L,
                    ),
                ),
            )
            index.persistReleaseDetail(detail)
            assertEquals("Preserved local title", db.catalogDao().recordingById(id)!!.title)
            assertEquals(99_000L, db.catalogDao().recordingById(id)!!.durationMs)
            val cached = index.cachedRelease(release)!!.tracks.single()
            assertEquals("Magnetic (edition)", cached.title)
            assertEquals(160_000L, cached.durationMs)
            assertEquals("Magnetic (sped up)", cached.recordingTitle)
            assertEquals(123_000L, cached.recordingDurationMs)
        } finally { db.close() }
    }

    @Test fun releaseGroupAndEditionsArePersistedWithoutAudioOrTrackIdentityCollisions() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            var serial = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "local-${++serial}" })
            val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
            val recording = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val worldwide = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val japan = "271b31f8-8ab6-483c-97d9-8f0fabb2d4f4"
            val details = listOf(
                MusicBrainzCatalogReleaseDetail(worldwide, group, "SUPER REAL ME", "ILLIT", "XW", "2024-03-25",
                    listOf(MusicBrainzCatalogTrack("14f273b6-813b-4239-b0f8-c4b839b902c6",recording,"Magnetic",1,2,160000,
                        artistDisplay = "ILLIT feat. Guest", recordingArtistDisplay = "ILLIT"))),
                MusicBrainzCatalogReleaseDetail(japan, group, "SUPER REAL ME", "ILLIT", "JP", "2024-03-25",
                    listOf(MusicBrainzCatalogTrack("bce9c8f8-b7a6-4427-9f46-97e88f8ae5f7",recording,"Magnetic",1,2,160000))),
            )
            val r1 = index.persistReleaseDetail(details[0])!!
            val r2 = index.persistReleaseDetail(details[1])!!
            assertNotEquals(r1, r2)
            val dao = db.catalogDao()
            val groupId = dao.entityIdForExternalIdentity("musicbrainz", "release-group", group)!!
            assertEquals(setOf(r1,r2), dao.catalogGroupEditions(groupId).map { it.releaseId }.toSet())
            assertEquals("14f273b6-813b-4239-b0f8-c4b839b902c6",
                dao.catalogMusicBrainzTracks(r1).single().trackMbid)
            assertEquals("bce9c8f8-b7a6-4427-9f46-97e88f8ae5f7",
                dao.catalogMusicBrainzTracks(r2).single().trackMbid)
            assertEquals(dao.catalogMusicBrainzTracks(r1).single().recordingId,
                dao.catalogMusicBrainzTracks(r2).single().recordingId)
            assertEquals(4, serial) // 1 Recording + 1 ReleaseGroup + 2 Releases; no reclaims
            assertEquals(r1, index.persistReleaseDetail(details[0]))
            val canonicalRecordingId = dao.catalogMusicBrainzTracks(r1).single().recordingId
            assertEquals("ILLIT", dao.recordingById(canonicalRecordingId)?.artistDisplay)
            assertEquals("ILLIT feat. Guest", index.cachedRelease(worldwide)!!.tracks.single().artistDisplay)
            assertEquals(emptyList<String>(), dao.musicBrainzRecordingMbidsWithAssets(listOf(recording)))
        } finally { db.close() }
    }
}
