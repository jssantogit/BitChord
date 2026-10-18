package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.identity.CanonicalRecordingIdentityDiscoveryRepository
import com.music.bitchord.data.catalog.identity.RecordingIdentityDiscoveryProvider
import com.music.bitchord.data.catalog.identity.RecordingIdentityResolutionResult
import com.music.bitchord.data.catalog.identity.StrongRecordingIdentityCandidate
import com.music.bitchord.data.catalog.identity.StrongRecordingIdentityLookupResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class ExternalIdentityAtomicClaimTest {

    @Test
    fun concurrentClaimsHaveExactlyOneWinnerAndNeverTransferOwnership() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "One", "Artist", 1L))
            dao.upsertRecording(RecordingEntity("r2", "Two", "Artist", 1L))
            val start = CompletableDeferred<Unit>()

            val claims = listOf("r1", "r2").map { recordingId ->
                async(Dispatchers.Default) {
                    start.await()
                    dao.insertExternalIdentityIfAbsent(identity(recordingId, MBID_A))
                }
            }
            start.complete(Unit)

            val results = claims.awaitAll()
            assertEquals(1, results.count { it != -1L })

            val owner = dao.entityIdForExternalIdentity(
                provider = "musicbrainz",
                entityType = "recording",
                externalId = MBID_A,
            )
            assertTrue(owner == "r1" || owner == "r2")

            val loser = if (owner == "r1") "r2" else "r1"
            assertEquals(-1L, dao.insertExternalIdentityIfAbsent(identity(loser, MBID_A)))
            assertEquals(
                owner,
                dao.entityIdForExternalIdentity("musicbrainz", "recording", MBID_A),
            )
        }
    }

    @Test
    fun existingClaimRejectsDifferentOwnerWithoutMutation() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "One", "Artist", 1L))
            dao.upsertRecording(RecordingEntity("r2", "Two", "Artist", 1L))

            assertTrue(dao.insertExternalIdentityIfAbsent(identity("r1", MBID_A)) != -1L)
            assertEquals(-1L, dao.insertExternalIdentityIfAbsent(identity("r2", MBID_A)))
            assertEquals(
                "r1",
                dao.entityIdForExternalIdentity("musicbrainz", "recording", MBID_A),
            )
        }
    }

    @Test
    fun concurrentDifferentMbidsForSameRecordingHaveExactlyOneExclusiveWinner() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "One", "Artist", 1L))
            val start = CompletableDeferred<Unit>()

            val claims = listOf(MBID_A, MBID_B).map { mbid ->
                async(Dispatchers.Default) {
                    start.await()
                    dao.claimExternalIdentityExclusivelyForEntity(identity("r1", mbid))
                }
            }
            start.complete(Unit)

            val results = claims.awaitAll()
            val persisted = dao.externalIdentitiesForEntity("musicbrainz", "recording", "r1")

            assertEquals(1, persisted.size)
            assertTrue(persisted.single().externalId == MBID_A || persisted.single().externalId == MBID_B)
            assertEquals(1, results.count { it.inserted })
            assertEquals(1, results.count { it.existingExternalIdsForEntity.isNotEmpty() })
        }
    }

    @Test
    fun concurrentResolversForSameRecordingCannotPersistDivergentMbids() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "One", "Artist", 1L))
            dao.upsertExternalIdentity(
                ExternalIdentityEntity("isrc", "recording", ISRC, "r1"),
            )

            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val firstRepository = CanonicalRecordingIdentityDiscoveryRepository(
                dao,
                GatedDiscoveryProvider(firstEntered, secondEntered, release, MBID_A),
            )
            val secondRepository = CanonicalRecordingIdentityDiscoveryRepository(
                dao,
                GatedDiscoveryProvider(secondEntered, firstEntered, release, MBID_B),
            )

            val resolutions = listOf(
                async(Dispatchers.Default) {
                    firstRepository.resolveMusicBrainzRecordingIdentity("r1")
                },
                async(Dispatchers.Default) {
                    secondRepository.resolveMusicBrainzRecordingIdentity("r1")
                },
            )

            firstEntered.await()
            secondEntered.await()
            release.complete(Unit)

            val results = resolutions.awaitAll()
            val persisted = dao.externalIdentitiesForEntity("musicbrainz", "recording", "r1")

            assertEquals(1, persisted.size)
            assertTrue(persisted.single().externalId == MBID_A || persisted.single().externalId == MBID_B)
            assertEquals(1, results.count { it is RecordingIdentityResolutionResult.Promoted })
            assertEquals(1, results.count { it is RecordingIdentityResolutionResult.AmbiguousExistingIdentity })
        }
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

    private fun identity(recordingId: String, externalId: String) = ExternalIdentityEntity(
        provider = "musicbrainz",
        entityType = "recording",
        externalId = externalId,
        entityId = recordingId,
    )

    private class GatedDiscoveryProvider(
        private val entered: CompletableDeferred<Unit>,
        private val otherEntered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
        private val mbid: String,
    ) : RecordingIdentityDiscoveryProvider {
        override val providerKey: String = "musicbrainz"

        override suspend fun lookupByIsrc(isrc: String): StrongRecordingIdentityLookupResult {
            entered.complete(Unit)
            otherEntered.await()
            release.await()
            return StrongRecordingIdentityLookupResult.Success(
                listOf(StrongRecordingIdentityCandidate("musicbrainz", mbid)),
            )
        }
    }

    companion object {
        private const val ISRC = "USS1Z9900001"
        private const val MBID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val MBID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    }
}
