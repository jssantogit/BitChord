package com.music.bitchord

import androidx.room.Room
import androidx.room.withTransaction
import com.music.bitchord.data.catalog.*
import com.music.bitchord.data.catalog.metadata.preview.*
import com.music.bitchord.data.catalog.metadata.promotion.*
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
class GuardedRecordingCreditPromotionRoomTest {
    private val recordingMbid = "11111111-1111-1111-1111-111111111111"
    private val artistMbid = "22222222-2222-2222-2222-222222222222"
    private val differentMbid = "33333333-3333-3333-3333-333333333333"

    private suspend fun seed(db: CanonicalCatalogDatabase) {
        val dao = db.catalogDao()
        dao.upsertRecording(RecordingEntity("recording", "Track", "Untrusted artist", 100L))
        dao.upsertArtist(ArtistEntity("artist-a", "Canonical artist"))
        dao.upsertArtist(ArtistEntity("artist-b", "Different canonical artist"))
        dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", recordingMbid, "recording"))
        dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "artist", artistMbid, "artist-a"))
        dao.upsertExternalIdentity(ExternalIdentityEntity("isrc", "recording", "USS1Z9900001", "recording"))
        dao.upsertMediaAsset(MediaAssetEntity("asset", "recording", "local", "song",
            "content://song", null, null, null, null))
        dao.upsertMusicBrainzRecordingEnrichment(
            MusicBrainzRecordingEnrichmentEntity("recording", recordingMbid, "Cached", null, null, 12L))
        dao.upsertMusicBrainzRecordingArtistCredits(listOf(
            MusicBrainzRecordingArtistCreditEntity("recording", 0, artistMbid, "Wrong name", " & "),
            MusicBrainzRecordingArtistCreditEntity("recording", 1, artistMbid, "Repeated artist", null),
        ))
    }

    private suspend fun expected(db: CanonicalCatalogDatabase): RecordingArtistCreditPreview.ReadyForPromotion =
        RecordingArtistCreditPreviewRepository(db.catalogDao()).preview("recording")
            as RecordingArtistCreditPreview.ReadyForPromotion

    @Test fun exactPromotionWritesOnlyOrderedCreditsAndIsIdempotentAcrossDiskReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "credit-promotion-" + System.nanoTime() + ".db"
        var db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            seed(db)
            val dao = db.catalogDao()
            val expected = expected(db)
            val evidence = dao.musicBrainzRecordingArtistCredits("recording")
            val first = RecordingCreditPromotionRepository(dao).promoteIfUnchanged(expected)
            assertTrue(first is CreditPromotionResult.Applied)
            assertEquals(listOf("artist-a", "artist-a"),
                dao.recordingArtistCreditIds(listOf("recording")).map { it.artistId })
            assertEquals(listOf(0, 1), dao.recordingArtistCreditIds(listOf("recording")).map { it.position })
            assertTrue(RecordingCreditPromotionRepository(dao).promoteIfUnchanged(expected)
                is CreditPromotionResult.AlreadyApplied)
            assertEquals(evidence, dao.musicBrainzRecordingArtistCredits("recording"))
            assertEquals("recording", dao.entityIdForExternalIdentity(
                "isrc", "recording", "USS1Z9900001"))
            assertEquals("artist-a", dao.entityIdForExternalIdentity("musicbrainz", "artist", artistMbid))
            assertEquals("asset", dao.mediaAsset("local", "song")?.id)
            db.close()
            db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
                .allowMainThreadQueries().build()
            val reopened = db.catalogDao()
            assertTrue(RecordingCreditPromotionRepository(reopened).promoteIfUnchanged(expected)
                is CreditPromotionResult.AlreadyApplied)
            assertEquals(listOf("artist-a", "artist-a"),
                reopened.recordingArtistCreditIds(listOf("recording")).map { it.artistId })
            assertEquals(evidence, reopened.musicBrainzRecordingArtistCredits("recording"))
            db.openHelper.readableDatabase.query("PRAGMA user_version").use {
                assertTrue(it.moveToFirst())
                assertEquals(3, it.getInt(0))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun staleEvidenceAndDivergentIdentityRejectWithoutAnyCreditWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val dao = db.catalogDao()
            val expected = expected(db)
            dao.upsertMusicBrainzRecordingArtistCredits(listOf(
                MusicBrainzRecordingArtistCreditEntity("recording", 0, artistMbid, "Evidence CHANGED", " & ")))
            assertTrue(RecordingCreditPromotionRepository(dao).promoteIfUnchanged(expected)
                is CreditPromotionResult.Rejected)
            assertTrue(dao.recordingArtistCreditIds(listOf("recording")).isEmpty())
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", differentMbid, "recording"))
            val result = RecordingCreditPromotionRepository(dao).promoteIfUnchanged(expected)
            assertTrue(result is CreditPromotionResult.Rejected)
            assertTrue((result as CreditPromotionResult.Rejected).latest is RecordingArtistCreditPreview.IdentityConflict)
            assertTrue(dao.recordingArtistCreditIds(listOf("recording")).isEmpty())
        } finally { db.close() }
    }

    @Test fun existingCanonicalConflictNeverGetsOverwritten() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val dao = db.catalogDao()
            val expected = expected(db)
            dao.upsertRecordingArtistCredits(listOf(
                RecordingArtistCreditEntity("recording", 0, "artist-b")))
            val result = RecordingCreditPromotionRepository(dao).promoteIfUnchanged(expected)
            assertTrue(result is CreditPromotionResult.Rejected)
            assertTrue((result as CreditPromotionResult.Rejected).latest is RecordingArtistCreditPreview.Conflict)
            assertEquals(listOf("artist-b"),
                dao.recordingArtistCreditIds(listOf("recording")).map { it.artistId })
        } finally { db.close() }
    }

    @Test fun simultaneousPromotionsHaveSingleWinnerAndOneIdempotentNoOp() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val expected = expected(db)
            val repo = RecordingCreditPromotionRepository(db.catalogDao())
            val answers = listOf(
                async(Dispatchers.IO) { repo.promoteIfUnchanged(expected) },
                async(Dispatchers.IO) { repo.promoteIfUnchanged(expected) },
            ).awaitAll()
            assertEquals(1, answers.count { it is CreditPromotionResult.Applied })
            assertEquals(1, answers.count { it is CreditPromotionResult.AlreadyApplied })
            assertEquals(2, db.catalogDao().recordingArtistCreditIds(listOf("recording")).size)
        } finally { db.close() }
    }

    @Test fun concurrentProviderResyncTransactionBeatsStaleApprovalFailClosed() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val dao = db.catalogDao()
            val expected = expected(db)
            val writerEntered = CompletableDeferred<Unit>()
            val releaseWriter = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.IO) {
                db.withTransaction {
                    dao.upsertRecordingArtistCredits(listOf(
                        RecordingArtistCreditEntity("recording", 0, "artist-b")))
                    writerEntered.complete(Unit)
                    releaseWriter.await()
                }
            }
            try {
                writerEntered.await()
                val promote = async(Dispatchers.IO) {
                    RecordingCreditPromotionRepository(dao).promoteIfUnchanged(expected)
                }
                releaseWriter.complete(Unit)
                writer.await()
                assertTrue(promote.await() is CreditPromotionResult.Rejected)
                assertEquals(listOf("artist-b"),
                    dao.recordingArtistCreditIds(listOf("recording")).map { it.artistId })
            } finally {
                releaseWriter.complete(Unit)
                writer.await()
            }
        } finally { db.close() }
    }
}
