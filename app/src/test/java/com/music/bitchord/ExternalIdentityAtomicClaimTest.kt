package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.RecordingEntity
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
                    dao.insertExternalIdentityIfAbsent(identity(recordingId))
                }
            }
            start.complete(Unit)

            val results = claims.awaitAll()
            assertEquals(1, results.count { it != -1L })

            val owner = dao.entityIdForExternalIdentity(
                provider = "musicbrainz",
                entityType = "recording",
                externalId = MBID,
            )
            assertTrue(owner == "r1" || owner == "r2")

            val loser = if (owner == "r1") "r2" else "r1"
            assertEquals(-1L, dao.insertExternalIdentityIfAbsent(identity(loser)))
            assertEquals(
                owner,
                dao.entityIdForExternalIdentity("musicbrainz", "recording", MBID),
            )
        }
    }

    @Test
    fun existingClaimRejectsDifferentOwnerWithoutMutation() = runBlocking {
        withDatabase { database ->
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "One", "Artist", 1L))
            dao.upsertRecording(RecordingEntity("r2", "Two", "Artist", 1L))

            assertTrue(dao.insertExternalIdentityIfAbsent(identity("r1")) != -1L)
            assertEquals(-1L, dao.insertExternalIdentityIfAbsent(identity("r2")))
            assertEquals(
                "r1",
                dao.entityIdForExternalIdentity("musicbrainz", "recording", MBID),
            )
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

    private fun identity(recordingId: String) = ExternalIdentityEntity(
        provider = "musicbrainz",
        entityType = "recording",
        externalId = MBID,
        entityId = recordingId,
    )

    companion object {
        private const val MBID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    }
}
