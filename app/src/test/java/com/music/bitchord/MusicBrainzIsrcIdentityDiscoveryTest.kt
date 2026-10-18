package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.identity.CanonicalRecordingIdentityDiscoveryRepository
import com.music.bitchord.data.catalog.identity.MusicBrainzRecordingIdentityDiscoveryProvider
import com.music.bitchord.data.catalog.identity.RecordingIdentityDiscoveryFailure
import com.music.bitchord.data.catalog.identity.RecordingIdentityDiscoveryProvider
import com.music.bitchord.data.catalog.identity.RecordingIdentityResolutionResult
import com.music.bitchord.data.catalog.identity.StrongRecordingIdentityCandidate
import com.music.bitchord.data.catalog.identity.StrongRecordingIdentityLookupResult
import com.music.bitchord.data.catalog.metadata.MetadataLookupResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzMetadataEnrichmentProvider
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class MusicBrainzIsrcIdentityDiscoveryTest {

    @Test
    fun exactMusicBrainzLookupUsesOnlyIsrcPathAndNoQuerySearch() = runBlocking {
        val transport = FakeMusicBrainzTransport { request ->
            assertEquals("isrc/$ISRC_A", request.path)
            assertEquals(mapOf("fmt" to "json"), request.parameters)
            isrcResponse(ISRC_A, listOf(MBID_A))
        }
        val provider = MusicBrainzRecordingIdentityDiscoveryProvider(client(transport))

        val result = provider.lookupByIsrc(ISRC_A)

        assertTrue(result is StrongRecordingIdentityLookupResult.Success)
        val candidates = (result as StrongRecordingIdentityLookupResult.Success).candidates
        assertEquals(listOf(StrongRecordingIdentityCandidate("musicbrainz", MBID_A)), candidates)
        assertTrue(transport.requests.none { "query" in it.parameters || it.path.contains("query=") })
    }

    @Test
    fun invalidIsrcNeverConsumesNetwork() = runBlocking {
        val transport = FakeMusicBrainzTransport { error("network must not be used") }
        val provider = MusicBrainzRecordingIdentityDiscoveryProvider(client(transport))

        val result = provider.lookupByIsrc("not-an-isrc")

        assertEquals(
            StrongRecordingIdentityLookupResult.Failure(RecordingIdentityDiscoveryFailure.INVALID_IDENTIFIER),
            result,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun zeroAndMultipleDistinctRecordingResultsRemainProviderNeutral() = runBlocking {
        var response = isrcResponse(ISRC_A, emptyList())
        val transport = FakeMusicBrainzTransport { response }
        val provider = MusicBrainzRecordingIdentityDiscoveryProvider(client(transport))

        val zero = provider.lookupByIsrc(ISRC_A) as StrongRecordingIdentityLookupResult.Success
        assertTrue(zero.candidates.isEmpty())

        response = isrcResponse(ISRC_A, listOf(MBID_A, MBID_B, MBID_A))
        val multiple = provider.lookupByIsrc(ISRC_A) as StrongRecordingIdentityLookupResult.Success
        assertEquals(
            listOf(
                StrongRecordingIdentityCandidate("musicbrainz", MBID_A),
                StrongRecordingIdentityCandidate("musicbrainz", MBID_B),
            ),
            multiple.candidates,
        )
    }

    @Test
    fun malformedAndTransportFailuresDoNotInventCandidates() = runBlocking {
        var mode = 0
        val transport = FakeMusicBrainzTransport {
            when (mode) {
                0 -> "{ definitely-not-json"
                else -> error("HTTP 503 private detail")
            }
        }
        val provider = MusicBrainzRecordingIdentityDiscoveryProvider(client(transport))

        assertEquals(
            StrongRecordingIdentityLookupResult.Failure(RecordingIdentityDiscoveryFailure.MALFORMED_RESPONSE),
            provider.lookupByIsrc(ISRC_A),
        )
        mode = 1
        assertEquals(
            StrongRecordingIdentityLookupResult.Failure(RecordingIdentityDiscoveryFailure.UNAVAILABLE),
            provider.lookupByIsrc(ISRC_A),
        )
    }

    @Test
    fun existingSingleMusicBrainzIdentityShortCircuitsDiscoveryWithoutNetwork() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "Track", "Artist", 1L))
            dao.upsertExternalIdentity(identity("musicbrainz", MBID_A, "r1"))
            dao.upsertExternalIdentity(identity("isrc", ISRC_A, "r1"))
            val provider = FakeDiscoveryProvider { error("lookup must be skipped") }
            val repository = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)

            val result = repository.resolveMusicBrainzRecordingIdentity("r1")

            assertEquals(RecordingIdentityResolutionResult.AlreadyResolved(MBID_A), result)
            assertTrue(provider.calls.isEmpty())
        }
    }

    @Test
    fun multipleExistingMusicBrainzIdentitiesAreAmbiguousWithoutNetwork() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "Track", "Artist", 1L))
            dao.upsertExternalIdentity(identity("musicbrainz", MBID_A, "r1"))
            dao.upsertExternalIdentity(identity("musicbrainz", MBID_B, "r1"))
            dao.upsertExternalIdentity(identity("isrc", ISRC_A, "r1"))
            val provider = FakeDiscoveryProvider { error("lookup must be skipped") }
            val repository = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)

            val result = repository.resolveMusicBrainzRecordingIdentity("r1")

            assertTrue(result is RecordingIdentityResolutionResult.AmbiguousExistingIdentity)
            assertTrue(provider.calls.isEmpty())
        }
    }

    @Test
    fun noIsrcReturnsNoStrongIdentifierWithoutNetwork() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "Track", "Artist", 1L))
            val provider = FakeDiscoveryProvider { error("lookup must be skipped") }
            val repository = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)

            assertEquals(
                RecordingIdentityResolutionResult.NoStrongIdentifier,
                repository.resolveMusicBrainzRecordingIdentity("r1"),
            )
            assertTrue(provider.calls.isEmpty())
        }
    }

    @Test
    fun uniqueCandidatePromotesMbidAndSecondAttemptIsIdempotentWithoutNetwork() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "Track", "Artist", 1L))
            dao.upsertExternalIdentity(identity("isrc", ISRC_A, "r1"))
            val provider = FakeDiscoveryProvider {
                StrongRecordingIdentityLookupResult.Success(
                    listOf(StrongRecordingIdentityCandidate("musicbrainz", MBID_A)),
                )
            }
            val repository = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)

            assertEquals(
                RecordingIdentityResolutionResult.Promoted(MBID_A),
                repository.resolveMusicBrainzRecordingIdentity("r1"),
            )
            assertEquals(
                "r1",
                dao.entityIdForExternalIdentity("musicbrainz", "recording", MBID_A),
            )
            val callsAfterPromotion = provider.calls.size
            assertEquals(
                RecordingIdentityResolutionResult.AlreadyResolved(MBID_A),
                repository.resolveMusicBrainzRecordingIdentity("r1"),
            )
            assertEquals(callsAfterPromotion, provider.calls.size)
        }
    }

    @Test
    fun zeroResultIsUnresolvedAndMultipleResultIsAmbiguousWithNoWrite() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "Track", "Artist", 1L))
            dao.upsertExternalIdentity(identity("isrc", ISRC_A, "r1"))
            var candidates = emptyList<StrongRecordingIdentityCandidate>()
            val provider = FakeDiscoveryProvider { StrongRecordingIdentityLookupResult.Success(candidates) }
            val repository = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)

            assertEquals(
                RecordingIdentityResolutionResult.Unresolved,
                repository.resolveMusicBrainzRecordingIdentity("r1"),
            )
            assertTrue(dao.externalIdentitiesForEntity("musicbrainz", "recording", "r1").isEmpty())

            candidates = listOf(
                StrongRecordingIdentityCandidate("musicbrainz", MBID_A),
                StrongRecordingIdentityCandidate("musicbrainz", MBID_B),
            )
            assertTrue(
                repository.resolveMusicBrainzRecordingIdentity("r1") is
                    RecordingIdentityResolutionResult.AmbiguousLookup,
            )
            assertTrue(dao.externalIdentitiesForEntity("musicbrainz", "recording", "r1").isEmpty())
        }
    }

    @Test
    fun discoveredMbidClaimedByAnotherRecordingReturnsConflictAndNeverMerges() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "One", "Artist", 1L))
            dao.upsertRecording(RecordingEntity("r2", "Two", "Artist", 1L))
            dao.upsertExternalIdentity(identity("isrc", ISRC_A, "r1"))
            dao.upsertExternalIdentity(identity("musicbrainz", MBID_A, "r2"))
            val provider = FakeDiscoveryProvider {
                StrongRecordingIdentityLookupResult.Success(
                    listOf(StrongRecordingIdentityCandidate("musicbrainz", MBID_A)),
                )
            }
            val repository = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)

            val result = repository.resolveMusicBrainzRecordingIdentity("r1")

            assertEquals(RecordingIdentityResolutionResult.IdentityConflict(MBID_A, "r2"), result)
            assertEquals("r2", dao.entityIdForExternalIdentity("musicbrainz", "recording", MBID_A))
            assertEquals("r1", dao.entityIdForExternalIdentity("isrc", "recording", ISRC_A))
            assertEquals("One", dao.recordingById("r1")?.title)
            assertEquals("Two", dao.recordingById("r2")?.title)
        }
    }

    @Test
    fun multipleIsrcsMustConvergeAndZeroResultDoesNotBlockUniqueCandidate() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "Track", "Artist", 1L))
            dao.upsertExternalIdentity(identity("isrc", ISRC_A, "r1"))
            dao.upsertExternalIdentity(identity("isrc", ISRC_B, "r1"))
            var responses = mapOf(
                ISRC_A to listOf(MBID_A),
                ISRC_B to listOf(MBID_A),
            )
            val provider = FakeDiscoveryProvider { isrc ->
                StrongRecordingIdentityLookupResult.Success(
                    responses.getValue(isrc).map { StrongRecordingIdentityCandidate("musicbrainz", it) },
                )
            }
            var repository = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)

            assertEquals(
                RecordingIdentityResolutionResult.Promoted(MBID_A),
                repository.resolveMusicBrainzRecordingIdentity("r1"),
            )

            dao.upsertRecording(RecordingEntity("r-zero", "Zero", "Artist", 1L))
            dao.upsertExternalIdentity(identity("isrc", ISRC_A, "r-zero"))
            dao.upsertExternalIdentity(identity("isrc", ISRC_B, "r-zero"))
            responses = mapOf(ISRC_A to listOf(MBID_B), ISRC_B to emptyList())
            repository = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)
            assertEquals(
                RecordingIdentityResolutionResult.Promoted(MBID_B),
                repository.resolveMusicBrainzRecordingIdentity("r-zero"),
            )
        }
    }

    @Test
    fun divergentMultiIsrcEvidenceOrLaterFailureCausesNoPartialPromotion() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "Track", "Artist", 1L))
            dao.upsertExternalIdentity(identity("isrc", ISRC_A, "r1"))
            dao.upsertExternalIdentity(identity("isrc", ISRC_B, "r1"))
            var failSecond = false
            val provider = FakeDiscoveryProvider { isrc ->
                when {
                    isrc == ISRC_A -> StrongRecordingIdentityLookupResult.Success(
                        listOf(StrongRecordingIdentityCandidate("musicbrainz", MBID_A)),
                    )
                    failSecond -> StrongRecordingIdentityLookupResult.Failure(
                        RecordingIdentityDiscoveryFailure.UNAVAILABLE,
                    )
                    else -> StrongRecordingIdentityLookupResult.Success(
                        listOf(StrongRecordingIdentityCandidate("musicbrainz", MBID_B)),
                    )
                }
            }
            val repository = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)

            assertTrue(
                repository.resolveMusicBrainzRecordingIdentity("r1") is
                    RecordingIdentityResolutionResult.ConflictingCandidates,
            )
            assertTrue(dao.externalIdentitiesForEntity("musicbrainz", "recording", "r1").isEmpty())

            failSecond = true
            assertEquals(
                RecordingIdentityResolutionResult.Failed(RecordingIdentityDiscoveryFailure.UNAVAILABLE),
                repository.resolveMusicBrainzRecordingIdentity("r1"),
            )
            assertTrue(dao.externalIdentitiesForEntity("musicbrainz", "recording", "r1").isEmpty())
        }
    }

    @Test
    fun cancellationIsNeverSwallowed() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "Track", "Artist", 1L))
            dao.upsertExternalIdentity(identity("isrc", ISRC_A, "r1"))
            val provider = FakeDiscoveryProvider { throw CancellationException("cancel") }
            val repository = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)
            var cancelled = false

            try {
                repository.resolveMusicBrainzRecordingIdentity("r1")
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertTrue(dao.externalIdentitiesForEntity("musicbrainz", "recording", "r1").isEmpty())
        }
    }

    @Test
    fun isrcLookupAndTrustedMbidEnrichmentShareOneGlobalLimiterWindow() = runBlocking {
        var virtualTime = 0L
        val starts = mutableListOf<Long>()
        val paths = mutableListOf<String>()
        val transport = object : MusicBrainzTransport {
            override suspend fun get(
                path: String,
                parameters: Map<String, String>,
                userAgent: String,
            ): String {
                starts += virtualTime
                paths += path
                return when (path) {
                    "isrc/$ISRC_A" -> isrcResponse(ISRC_A, listOf(MBID_A))
                    "recording/$MBID_A" -> """{"id":"$MBID_A","title":"Track"}"""
                    else -> error("unexpected path $path")
                }
            }
        }
        val limiter = MusicBrainzRateLimiter(
            nowMillis = { virtualTime },
            delayMillis = { millis -> virtualTime += millis },
        )
        val sharedClient = MusicBrainzClient(transport, limiter, TEST_USER_AGENT)
        val discovery = MusicBrainzRecordingIdentityDiscoveryProvider(sharedClient)
        val enrichment = MusicBrainzMetadataEnrichmentProvider(sharedClient)

        assertTrue(discovery.lookupByIsrc(ISRC_A) is StrongRecordingIdentityLookupResult.Success)
        assertTrue(enrichment.lookupRecording(MBID_A) is MetadataLookupResult.Success)

        assertEquals(listOf("isrc/$ISRC_A", "recording/$MBID_A"), paths)
        assertEquals(2, starts.size)
        assertTrue(starts[1] - starts[0] >= 1_000L)
    }

    private suspend fun withDatabase(block: suspend (CanonicalCatalogDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun client(transport: FakeMusicBrainzTransport): MusicBrainzClient {
        var virtualTime = 0L
        return MusicBrainzClient(
            transport = transport,
            rateLimiter = MusicBrainzRateLimiter(
                nowMillis = { virtualTime },
                delayMillis = { millis -> virtualTime += millis },
            ),
            userAgent = TEST_USER_AGENT,
        )
    }

    private fun identity(provider: String, externalId: String, recordingId: String) =
        ExternalIdentityEntity(provider, "recording", externalId, recordingId)

    private class FakeDiscoveryProvider(
        private val responder: suspend (String) -> StrongRecordingIdentityLookupResult,
    ) : RecordingIdentityDiscoveryProvider {
        override val providerKey: String = "musicbrainz"
        val calls = mutableListOf<String>()

        override suspend fun lookupByIsrc(isrc: String): StrongRecordingIdentityLookupResult {
            calls += isrc
            return responder(isrc)
        }
    }

    private class FakeMusicBrainzTransport(
        private val responder: suspend (Request) -> String,
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
            val request = Request(path, parameters.toMap(), userAgent)
            requests += request
            return responder(request)
        }
    }

    companion object {
        private const val TEST_USER_AGENT = "Bitchord/test (https://github.com/jssantogit/BitChord)"
        private const val ISRC_A = "USS1Z9900001"
        private const val ISRC_B = "GBABC2400001"
        private const val MBID_A = "11111111-1111-1111-1111-111111111111"
        private const val MBID_B = "22222222-2222-2222-2222-222222222222"

        private fun isrcResponse(isrc: String, recordings: List<String>): String =
            """{"isrc":"$isrc","recordings":[${recordings.joinToString(",") { "{\"id\":\"$it\"}" }}]}"""
    }
}
