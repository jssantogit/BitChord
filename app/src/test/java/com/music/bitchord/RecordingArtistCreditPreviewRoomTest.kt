package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.MusicBrainzRecordingArtistCreditEntity
import com.music.bitchord.data.catalog.MusicBrainzRecordingEnrichmentEntity
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.metadata.preview.RecordingArtistCreditPreview
import com.music.bitchord.data.catalog.metadata.preview.RecordingArtistCreditPreviewRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class RecordingArtistCreditPreviewRoomTest {
    private val recordingMbid = "11111111-1111-1111-1111-111111111111"
    private val artistMbid = "22222222-2222-2222-2222-222222222222"
    private val collaboratorMbid = "33333333-3333-3333-3333-333333333333"

    @Test fun roomPreviewIsReadOnlyAcrossCloseReopenAndKeepsExactOrder() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "credit-preview-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "Original", "Original display", 100L))
            dao.upsertArtist(ArtistEntity("artist-a", "Unrelated canonical name"))
            dao.upsertArtist(ArtistEntity("artist-b", "Second canonical name"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", recordingMbid, "r1"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "artist", artistMbid, "artist-a"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "artist", collaboratorMbid, "artist-b"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("isrc", "recording", "USS1Z9900001", "r1"))
            dao.upsertMediaAsset(MediaAssetEntity("asset", "r1", "local", "one", "content://one", null, null, null, null))
            dao.upsertMusicBrainzRecordingEnrichment(
                MusicBrainzRecordingEnrichmentEntity("r1", recordingMbid, "MB cached", null, null, 101L))
            dao.upsertMusicBrainzRecordingArtistCredits(listOf(
                MusicBrainzRecordingArtistCreditEntity("r1", 0, artistMbid, "Artist A", " & "),
                MusicBrainzRecordingArtistCreditEntity("r1", 1, collaboratorMbid, "Artist B", " feat. "),
                MusicBrainzRecordingArtistCreditEntity("r1", 2, artistMbid, "Artist A again", null),
            ))
            val beforeCredits = dao.musicBrainzRecordingArtistCredits("r1")
            val preview = RecordingArtistCreditPreviewRepository(dao).preview("r1")
            val proposed = (preview as RecordingArtistCreditPreview.ReadyForPromotion).proposed
            assertEquals(listOf("artist-a", "artist-b", "artist-a"), proposed.map { it.artistId })
            assertEquals(listOf(0, 1, 2), proposed.map { it.position })
            assertTrue(dao.recordingArtistCredits("r1").isEmpty())
            assertEquals("r1", dao.entityIdForExternalIdentity("isrc", "recording", "USS1Z9900001"))
            assertEquals("asset", dao.mediaAsset("local", "one")?.id)
            assertEquals(beforeCredits, dao.musicBrainzRecordingArtistCredits("r1"))
            db.close()
            db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
                .allowMainThreadQueries().build()
            val reopened = db.catalogDao()
            assertEquals(preview, RecordingArtistCreditPreviewRepository(reopened).preview("r1"))
            assertTrue(reopened.recordingArtistCredits("r1").isEmpty())
            assertEquals("r1", reopened.entityIdForExternalIdentity("musicbrainz", "recording", recordingMbid))
            assertEquals("artist-a", reopened.entityIdForExternalIdentity("musicbrainz", "artist", artistMbid))
            assertEquals(beforeCredits, reopened.musicBrainzRecordingArtistCredits("r1"))
            assertEquals("asset", reopened.mediaAsset("local", "one")?.id)
            reopened.openHelper.readableDatabase.query("PRAGMA user_version").use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals(3, cur.getInt(0))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun roomExistingCanonicalCreditsYieldMatchesOrConflictWithoutWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("r", "R", "Artist", null))
            dao.upsertArtist(ArtistEntity("a", "A"))
            dao.upsertArtist(ArtistEntity("b", "B"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", recordingMbid, "r"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "artist", artistMbid, "a"))
            dao.upsertMusicBrainzRecordingEnrichment(
                MusicBrainzRecordingEnrichmentEntity("r", recordingMbid, null, null, null, 1L))
            dao.upsertMusicBrainzRecordingArtistCredits(
                listOf(MusicBrainzRecordingArtistCreditEntity("r", 0, artistMbid, "Wrong name", null)))
            dao.upsertRecordingArtistCredits(listOf(RecordingArtistCreditEntity("r", 0, "a")))
            val reader = RecordingArtistCreditPreviewRepository(dao)
            assertTrue(reader.preview("r") is RecordingArtistCreditPreview.AlreadyMatches)
            dao.replaceRecordingArtistCredits("r", listOf(RecordingArtistCreditEntity("r", 0, "b")))
            val conflict = reader.preview("r")
            assertTrue(conflict is RecordingArtistCreditPreview.Conflict)
            assertEquals("b", dao.recordingArtistCredits("r").single().artistId)
            assertEquals("a", (conflict as RecordingArtistCreditPreview.Conflict).proposed.single().artistId)
        } finally { db.close() }
    }

    @Test fun roomAliasDanglingAndStrongIdentityMismatchNeverProducePartialPlan() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("r", "R", "Artist", null))
            dao.upsertArtist(ArtistEntity("a", "A"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", recordingMbid, "r"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "artist", artistMbid, "deleted-artist"))
            dao.upsertMusicBrainzRecordingEnrichment(
                MusicBrainzRecordingEnrichmentEntity("r", recordingMbid, null, null, null, 1L))
            dao.upsertMusicBrainzRecordingArtistCredits(listOf(
                MusicBrainzRecordingArtistCreditEntity("r", 0, artistMbid, "A", null),
                MusicBrainzRecordingArtistCreditEntity("r", 1, collaboratorMbid, "B", null),
            ))
            val reader = RecordingArtistCreditPreviewRepository(dao)
            assertTrue(reader.preview("r") is RecordingArtistCreditPreview.Unresolved)
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "artist", collaboratorMbid, "a"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "artist", artistMbid, "a"))
            val conflict = reader.preview("r")
            assertTrue(conflict is RecordingArtistCreditPreview.IdentityConflict)
            assertTrue(dao.recordingArtistCredits("r").isEmpty())
            assertNull(dao.entityIdForExternalIdentity("opensubsonic", "recording", "nonexistent"))
        } finally { db.close() }
    }
}
