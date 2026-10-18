package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.metadata.CanonicalMetadataEnrichmentRepository
import com.music.bitchord.data.catalog.metadata.MetadataFieldSource
import com.music.bitchord.data.catalog.metadata.MetadataRefreshResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzMetadataEnrichmentProvider
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class MusicBrainzTrustedEnrichmentContractTest {

    @Test
    fun entityWithoutTrustedMusicBrainzIdentityDoesNotUseNetwork() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("recording-local", "Local title", "Local artist", 241_000L))
            val transport = FakeMusicBrainzTransport { recordingFixture(RECORDING_MBID, "Network title") }
            val repository = repository(dao = dao, transport = transport)

            assertEquals(MetadataRefreshResult.NoTrustedIdentity, repository.refreshRecording("recording-local"))
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun artistRecordingAndReleaseUseOnlyTheirExactTrustedMbid() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertArtist(ArtistEntity("artist-local", "Same name"))
            dao.upsertRecording(RecordingEntity("recording-local", "Track", "Same name", 1L))
            dao.upsertRelease(ReleaseEntity("release-local", "Album", "Same name", null))
            dao.upsertExternalIdentity(mbIdentity("artist", ARTIST_MBID, "artist-local"))
            dao.upsertExternalIdentity(mbIdentity("recording", RECORDING_MBID, "recording-local"))
            dao.upsertExternalIdentity(mbIdentity("release", RELEASE_MBID, "release-local"))

            val transport = FakeMusicBrainzTransport { path ->
                when (path) {
                    "artist/$ARTIST_MBID" -> artistFixture(ARTIST_MBID, "Same name")
                    "recording/$RECORDING_MBID" -> recordingFixture(RECORDING_MBID, "Track MB")
                    "release/$RELEASE_MBID" -> releaseFixture(RELEASE_MBID, "Album MB")
                    else -> error("Unexpected MusicBrainz path: $path")
                }
            }
            val repository = repository(dao = dao, transport = transport)

            assertTrue(repository.refreshArtist("artist-local") is MetadataRefreshResult.Refreshed)
            assertTrue(repository.refreshRecording("recording-local") is MetadataRefreshResult.Refreshed)
            assertTrue(repository.refreshRelease("release-local") is MetadataRefreshResult.Refreshed)

            assertEquals(
                listOf("artist/$ARTIST_MBID", "recording/$RECORDING_MBID", "release/$RELEASE_MBID"),
                transport.requests.map { it.path },
            )
            assertTrue(transport.requests.all { request -> "query" !in request.parameters })
            assertTrue(transport.requests.all { request -> request.parameters["fmt"] == "json" })
            assertTrue(transport.requests.all { request -> request.userAgent.contains("Bitchord") })
        }
    }

    @Test
    fun musicBrainzIdNeverBecomesCanonicalIdAndEqualNamesWithDifferentMbidsStayDistinct() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertArtist(ArtistEntity("artist-one", "Same name"))
            dao.upsertArtist(ArtistEntity("artist-two", "Same name"))
            dao.upsertExternalIdentity(mbIdentity("artist", ARTIST_MBID, "artist-one"))
            dao.upsertExternalIdentity(mbIdentity("artist", ARTIST_2_MBID, "artist-two"))
            val transport = FakeMusicBrainzTransport { path ->
                when (path) {
                    "artist/$ARTIST_MBID" -> artistFixture(ARTIST_MBID, "Same name")
                    "artist/$ARTIST_2_MBID" -> artistFixture(ARTIST_2_MBID, "Same name")
                    else -> error("unexpected path")
                }
            }
            val repository = repository(dao = dao, transport = transport)

            repository.refreshArtist("artist-one")
            repository.refreshArtist("artist-two")
            val first = assertNotNull(repository.resolveArtist("artist-one"))
            val second = assertNotNull(repository.resolveArtist("artist-two"))

            assertEquals("artist-one", first.artistId)
            assertEquals("artist-two", second.artistId)
            assertNotEquals(ARTIST_MBID, first.artistId)
            assertNotEquals(ARTIST_2_MBID, second.artistId)
            assertEquals(ARTIST_MBID, first.musicBrainzId)
            assertEquals(ARTIST_2_MBID, second.musicBrainzId)
        }
    }

    @Test
    fun normalizedMetadataIsProviderNeutralAndFieldFallbackCarriesProvenance() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("recording-local", "Canonical title", "Canonical artist", 241_000L))
            dao.upsertExternalIdentity(mbIdentity("recording", RECORDING_MBID, "recording-local"))
            val transport = FakeMusicBrainzTransport {
                recordingFixture(
                    mbid = RECORDING_MBID,
                    title = "MusicBrainz title",
                    length = null,
                    includeCredits = true,
                )
            }
            val provider = MusicBrainzMetadataEnrichmentProvider(
                MusicBrainzClient(transport = transport, userAgent = TEST_USER_AGENT),
            )
            val lookup = provider.lookupRecording(RECORDING_MBID)
            assertEquals("RecordingMetadataEnrichment", lookup.valueOrNull()!!::class.simpleName)
            val repository = CanonicalMetadataEnrichmentRepository(dao, provider) { 10_000L }

            repository.refreshRecording("recording-local")
            val resolved = assertNotNull(repository.resolveRecording("recording-local"))

            assertEquals("MusicBrainz title", resolved.title.value)
            assertEquals(MetadataFieldSource.MUSICBRAINZ, resolved.title.provenance?.source)
            assertEquals(RECORDING_MBID, resolved.title.provenance?.externalId)
            assertEquals(241_000L, resolved.durationMs.value)
            assertEquals(MetadataFieldSource.CANONICAL, resolved.durationMs.provenance?.source)
            assertEquals("Artist A feat. Guest", resolved.artistDisplay.value)
            assertEquals(MetadataFieldSource.MUSICBRAINZ, resolved.artistDisplay.provenance?.source)
        }
    }

    @Test
    fun successfulRefreshUpdatesCacheWhilePartialRefreshKeepsPriorMusicBrainzFields() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("recording-local", "Canonical", "Canonical artist", 123L))
            dao.upsertExternalIdentity(mbIdentity("recording", RECORDING_MBID, "recording-local"))
            var response = recordingFixture(RECORDING_MBID, "First title", length = 10_000L, includeCredits = true)
            val transport = FakeMusicBrainzTransport { response }
            var now = 100L
            val repository = repository(dao, transport) { now }

            assertTrue(repository.refreshRecording("recording-local") is MetadataRefreshResult.Refreshed)
            var resolved = assertNotNull(repository.resolveRecording("recording-local"))
            assertEquals("First title", resolved.title.value)
            assertEquals(10_000L, resolved.durationMs.value)
            assertEquals(100L, resolved.fetchedAtEpochMs)

            now = 200L
            response = """{"id":"$RECORDING_MBID","title":"Second title"}"""
            assertTrue(repository.refreshRecording("recording-local") is MetadataRefreshResult.Refreshed)
            resolved = assertNotNull(repository.resolveRecording("recording-local"))
            assertEquals("Second title", resolved.title.value)
            assertEquals(10_000L, resolved.durationMs.value)
            assertEquals("Artist A feat. Guest", resolved.artistDisplay.value)
            assertEquals(200L, resolved.fetchedAtEpochMs)
        }
    }

    @Test
    fun failedOrMalformedRefreshPreservesLastValidCache() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("recording-local", "Canonical", "Canonical artist", 123L))
            dao.upsertExternalIdentity(mbIdentity("recording", RECORDING_MBID, "recording-local"))
            var mode = 0
            val transport = FakeMusicBrainzTransport {
                when (mode) {
                    0 -> recordingFixture(RECORDING_MBID, "Cached title", 55_000L, includeCredits = true)
                    1 -> throw IOException("HTTP 503 https://musicbrainz.org/private-details")
                    else -> "{ definitely-not-json"
                }
            }
            val repository = repository(dao, transport)

            assertTrue(repository.refreshRecording("recording-local") is MetadataRefreshResult.Refreshed)
            mode = 1
            assertTrue(repository.refreshRecording("recording-local") is MetadataRefreshResult.Failed)
            var resolved = assertNotNull(repository.resolveRecording("recording-local"))
            assertEquals("Cached title", resolved.title.value)
            assertEquals(55_000L, resolved.durationMs.value)

            mode = 2
            assertTrue(repository.refreshRecording("recording-local") is MetadataRefreshResult.Failed)
            resolved = assertNotNull(repository.resolveRecording("recording-local"))
            assertEquals("Cached title", resolved.title.value)
            assertEquals(55_000L, resolved.durationMs.value)
        }
    }

    @Test
    fun emptyCacheAndNetworkFailureFallsBackToCanonicalWithoutCorruption() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertArtist(ArtistEntity("artist-local", "Canonical artist"))
            dao.upsertExternalIdentity(mbIdentity("artist", ARTIST_MBID, "artist-local"))
            val transport = FakeMusicBrainzTransport { throw IOException("503 secret-url") }
            val repository = repository(dao, transport)

            assertTrue(repository.refreshArtist("artist-local") is MetadataRefreshResult.Failed)
            val resolved = assertNotNull(repository.resolveArtist("artist-local"))
            assertEquals("Canonical artist", resolved.name.value)
            assertEquals(MetadataFieldSource.CANONICAL, resolved.name.provenance?.source)
            assertNull(resolved.sortName.value)
        }
    }

    @Test
    fun cacheOnlyReadsConsumeNoNetworkQuota() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRelease(ReleaseEntity("release-local", "Canonical album", "Canonical artist", null))
            dao.upsertExternalIdentity(mbIdentity("release", RELEASE_MBID, "release-local"))
            val transport = FakeMusicBrainzTransport { releaseFixture(RELEASE_MBID, "MB album") }
            val repository = repository(dao, transport)
            repository.refreshRelease("release-local")
            val callsAfterRefresh = transport.requests.size

            repeat(5) {
                val resolved = assertNotNull(repository.resolveRelease("release-local"))
                assertEquals("MB album", resolved.title.value)
            }
            assertEquals(callsAfterRefresh, transport.requests.size)
        }
    }

    @Test
    fun orderedMusicBrainzCreditsRemainEvidenceAndNeverMergeCanonicalArtistsByName() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertArtist(ArtistEntity("canonical-a", "Artist A"))
            dao.upsertArtist(ArtistEntity("canonical-guest", "Guest"))
            dao.upsertArtist(ArtistEntity("same-name-other", "Artist A"))
            dao.upsertRecording(RecordingEntity("recording-local", "Track", "Artist A feat. Guest", 1L))
            dao.upsertRecordingArtistCredits(
                listOf(
                    RecordingArtistCreditEntity("recording-local", 0, "canonical-a"),
                    RecordingArtistCreditEntity("recording-local", 1, "canonical-guest"),
                ),
            )
            dao.upsertExternalIdentity(mbIdentity("recording", RECORDING_MBID, "recording-local"))
            val transport = FakeMusicBrainzTransport {
                recordingFixture(RECORDING_MBID, "Track", includeCredits = true)
            }
            val repository = repository(dao, transport)

            repository.refreshRecording("recording-local")
            val resolved = assertNotNull(repository.resolveRecording("recording-local"))
            assertEquals(
                listOf(ARTIST_MBID, ARTIST_2_MBID),
                resolved.musicBrainzArtistCredits.map { it.musicBrainzArtistId },
            )
            assertEquals(listOf("Artist A", "Guest"), resolved.musicBrainzArtistCredits.map { it.name })
            assertEquals(
                listOf("canonical-a", "canonical-guest"),
                dao.recordingArtistCredits("recording-local").map { it.artistId },
            )
            assertEquals(3, dao.recordingsByArtist("same-name-other").size + 3 - 3)
            assertTrue(dao.recordingsByArtist("same-name-other").isEmpty())
        }
    }

    @Test
    fun roomBackedEnrichmentSurvivesReopenAndFailedRefresh() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "musicbrainz-enrichment-${System.nanoTime()}.db"
        val transport = FakeMusicBrainzTransport {
            recordingFixture(RECORDING_MBID, "Persisted MB title", length = null, includeCredits = true)
        }

        var database = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        database.catalogDao().apply {
            upsertRecording(RecordingEntity("recording-local", "Canonical title", "Canonical artist", 241_000L))
            upsertExternalIdentity(mbIdentity("recording", RECORDING_MBID, "recording-local"))
        }
        var repository = repository(database.catalogDao(), transport) { 77L }
        assertTrue(repository.refreshRecording("recording-local") is MetadataRefreshResult.Refreshed)
        var resolved = assertNotNull(repository.resolveRecording("recording-local"))
        assertEquals("Persisted MB title", resolved.title.value)
        assertEquals(241_000L, resolved.durationMs.value)
        database.close()

        database = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        val failingTransport = FakeMusicBrainzTransport { throw IOException("offline") }
        repository = repository(database.catalogDao(), failingTransport) { 88L }
        resolved = assertNotNull(repository.resolveRecording("recording-local"))
        assertEquals("Persisted MB title", resolved.title.value)
        assertEquals(MetadataFieldSource.MUSICBRAINZ, resolved.title.provenance?.source)
        assertEquals(241_000L, resolved.durationMs.value)
        assertEquals(MetadataFieldSource.CANONICAL, resolved.durationMs.provenance?.source)
        assertTrue(repository.refreshRecording("recording-local") is MetadataRefreshResult.Failed)
        assertEquals("Persisted MB title", assertNotNull(repository.resolveRecording("recording-local")).title.value)

        database.close()
        context.deleteDatabase(name)
    }

    private suspend fun <T> withDatabase(block: suspend (CanonicalCatalogDatabase) -> T): T {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun repository(
        dao: com.music.bitchord.data.catalog.CanonicalCatalogDao,
        transport: FakeMusicBrainzTransport,
        now: () -> Long = { 1_000L },
    ): CanonicalMetadataEnrichmentRepository {
        val provider = MusicBrainzMetadataEnrichmentProvider(
            MusicBrainzClient(transport = transport, userAgent = TEST_USER_AGENT),
        )
        return CanonicalMetadataEnrichmentRepository(dao, provider, now)
    }

    private fun mbIdentity(entityType: String, mbid: String, entityId: String) =
        ExternalIdentityEntity(
            provider = "musicbrainz",
            entityType = entityType,
            externalId = mbid,
            entityId = entityId,
        )

    private class FakeMusicBrainzTransport(
        private val responder: suspend (String) -> String,
    ) : MusicBrainzTransport {
        data class Request(
            val path: String,
            val parameters: Map<String, String>,
            val userAgent: String,
        )

        val requests = mutableListOf<Request>()

        override suspend fun get(
            path: String,
            parameters: Map<String, String>,
            userAgent: String,
        ): String {
            requests += Request(path, parameters.toMap(), userAgent)
            return responder(path)
        }
    }

    private fun <T> com.music.bitchord.data.catalog.metadata.MetadataLookupResult<T>.valueOrNull(): T? =
        (this as? com.music.bitchord.data.catalog.metadata.MetadataLookupResult.Success<T>)?.value

    companion object {
        private const val TEST_USER_AGENT = "Bitchord/test (https://github.com/jssantogit/BitChord)"
        private const val ARTIST_MBID = "11111111-1111-1111-1111-111111111111"
        private const val ARTIST_2_MBID = "33333333-3333-3333-3333-333333333333"
        private const val RECORDING_MBID = "22222222-2222-2222-2222-222222222222"
        private const val RELEASE_MBID = "44444444-4444-4444-4444-444444444444"

        private fun artistFixture(mbid: String, name: String): String =
            """{"id":"$mbid","name":"$name","sort-name":"Name, $name","disambiguation":"fixture","country":"BR","area":{"name":"Bahia"}}"""

        private fun recordingFixture(
            mbid: String,
            title: String,
            length: Long? = null,
            includeCredits: Boolean = false,
        ): String {
            val lengthJson = length?.toString() ?: "null"
            val credits = if (includeCredits) {
                """,\"artist-credit\":[{\"name\":\"Artist A\",\"joinphrase\":\" feat. \",\"artist\":{\"id\":\"$ARTIST_MBID\",\"name\":\"Artist A\"}},{\"name\":\"Guest\",\"artist\":{\"id\":\"$ARTIST_2_MBID\",\"name\":\"Guest\"}}]"""
            } else ""
            return """{"id":"$mbid","title":"$title","length":$lengthJson$credits}"""
                .replace("\\\"", "\"")
        }

        private fun releaseFixture(mbid: String, title: String): String =
            """{"id":"$mbid","title":"$title","date":"2025-01-02","country":"BR","status":"Official","artist-credit":[{"name":"Artist A","artist":{"id":"$ARTIST_MBID","name":"Artist A"}}]}"""
    }
}
