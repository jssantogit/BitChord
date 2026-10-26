package com.music.bitchord

import androidx.room.Room
import androidx.room.withTransaction
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.MusicBrainzRecordingArtistCreditEntity
import com.music.bitchord.data.catalog.MusicBrainzRecordingEnrichmentEntity
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.metadata.preview.BatchPreviewOutcome
import com.music.bitchord.data.catalog.metadata.preview.RecordingArtistCreditBatchPreviewRepository
import com.music.bitchord.data.catalog.metadata.preview.RecordingArtistCreditPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class RecordingArtistCreditBatchPreviewRoomTest {
    private val knownArtistMbid = "22222222-2222-2222-2222-222222222222"
    private val unknownArtistMbid = "33333333-3333-3333-3333-333333333333"
    private fun mbid(number: Int) = "%08x-1111-1111-1111-111111111111".format(number)

    private suspend fun seed(db: CanonicalCatalogDatabase) {
        val dao = db.catalogDao()
        dao.upsertArtist(ArtistEntity("artist-a", "Canonical A"))
        dao.upsertArtist(ArtistEntity("artist-b", "Canonical B"))
        dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "artist", knownArtistMbid, "artist-a"))
        for (i in 1..6) {
            val id = "r" + i
            val trusted = mbid(i)
            dao.upsertRecording(RecordingEntity(id, "Song " + i, "Untrusted display", 100L))
            if (i != 5) {
                dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", trusted, id))
                dao.upsertMusicBrainzRecordingEnrichment(
                    MusicBrainzRecordingEnrichmentEntity(id, trusted, null, null, null, 123L))
                dao.upsertMusicBrainzRecordingArtistCredits(listOf(
                    MusicBrainzRecordingArtistCreditEntity(id, 0,
                        if (i == 4) unknownArtistMbid else knownArtistMbid, "Evidence", null)))
            }
        }
        dao.upsertRecordingArtistCredits(listOf(
            RecordingArtistCreditEntity("r2", 0, "artist-a"),
            RecordingArtistCreditEntity("r3", 0, "artist-b"),
        ))
        dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", mbid(16), "r6"))
        dao.upsertExternalIdentity(ExternalIdentityEntity("isrc", "recording", "USS1Z9900001", "r1"))
        dao.upsertMediaAsset(MediaAssetEntity(
            "asset-1", "r1", "local", "song", "content://song", null, null, null, null))
    }

    @Test fun roomBatchMixedResultsRemainReadOnlyAfterCloseAndReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "batch-preview-" + System.nanoTime() + ".db"
        var db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            seed(db)
            val dao = db.catalogDao()
            val beforeEvidence = (1..6).associate { ("r" + it) to
                dao.musicBrainzRecordingArtistCredits("r" + it) }
            val beforeCanonical = (1..6).associate { ("r" + it) to
                dao.recordingArtistCreditIds(listOf("r" + it)) }
            val ids = (6 downTo 1).map { "r" + it } + "r1"
            val result = RecordingArtistCreditBatchPreviewRepository(dao).previewBatch(ids)
            assertEquals((1..6).map { "r" + it }, result.results.map { it.recordingId })
            assertTrue(result.countsByOutcome.values.all { it == 1 })
            assertEquals(1, result.countsByOutcome[BatchPreviewOutcome.IDENTITY_CONFLICT])
            assertEquals("artist-a", dao.entityIdForExternalIdentity("musicbrainz", "artist", knownArtistMbid))
            assertEquals("asset-1", dao.mediaAsset("local", "song")?.id)
            assertEquals("r1", dao.entityIdForExternalIdentity("isrc", "recording", "USS1Z9900001"))
            db.close()
            db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
                .allowMainThreadQueries().build()
            val reopened = db.catalogDao()
            assertEquals(result, RecordingArtistCreditBatchPreviewRepository(reopened).previewBatch(ids))
            assertEquals(beforeEvidence, (1..6).associate { ("r" + it) to
                reopened.musicBrainzRecordingArtistCredits("r" + it) })
            assertEquals(beforeCanonical, (1..6).associate { ("r" + it) to
                reopened.recordingArtistCreditIds(listOf("r" + it)) })
            assertEquals("artist-a", reopened.entityIdForExternalIdentity("musicbrainz", "artist", knownArtistMbid))
            assertEquals("asset-1", reopened.mediaAsset("local", "song")?.id)
            db.openHelper.readableDatabase.query("PRAGMA user_version").use {
                assertTrue(it.moveToFirst())
                assertEquals(9, it.getInt(0))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun concurrentResyncCannotProduceHybridBatchSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val dao = db.catalogDao()
            val firstWriteDone = CompletableDeferred<Unit>()
            val finishWrite = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.IO) {
                db.withTransaction {
                    dao.replaceRecordingArtistCredits("r1",
                        listOf(RecordingArtistCreditEntity("r1", 0, "artist-b")))
                    firstWriteDone.complete(Unit)
                    finishWrite.await()
                    dao.replaceRecordingArtistCredits("r2",
                        listOf(RecordingArtistCreditEntity("r2", 0, "artist-b")))
                }
            }
            try {
                firstWriteDone.await()
                val reader = async(Dispatchers.IO) {
                    RecordingArtistCreditBatchPreviewRepository(dao).previewBatch(listOf("r2", "r1"))
                }
                finishWrite.complete(Unit)
                writer.await()
                val results = reader.await().results
                assertEquals(listOf("r1", "r2"), results.map { it.recordingId })
                // A reader racing an atomic provider sync may see either committed version,
                // but NEVER a mixture of old and new canonical credit states.
                assertEquals(results[0] is RecordingArtistCreditPreview.Conflict,
                    results[1] is RecordingArtistCreditPreview.Conflict)
            } finally {
                finishWrite.complete(Unit)
                writer.await()
            }
        } finally { db.close() }
    }

    @Test fun requestLimitIsRejectedBeforeAnyRoomRead() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RecordingArtistCreditBatchPreviewRepository(db.catalogDao())
            try {
                repo.previewBatch((1..33).map { "r" + it })
                fail("Overlarge preview request must fail closed")
            } catch (_: IllegalArgumentException) {
                // Expected: no truncated or partial batch.
            }
        } finally { db.close() }
    }
}
