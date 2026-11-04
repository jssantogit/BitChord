package com.music.bitchord.data.listening

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityRepository
import com.music.bitchord.data.catalog.CanonicalLibraryProviderConfigurationReader
import com.music.bitchord.data.catalog.RoomCanonicalLibraryAvailabilityStore
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.MediaAssetTagsEntity
import com.music.bitchord.data.catalog.RecordingEntity
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
class ComposerPathScopedRoomTest {
    private fun filter(field: SmartPlaylistRuleField,
                       operator: SmartPlaylistRuleOperator, text: String? = null) =
        SavedSmartPlaylistV2Filter("filter", "Filter",
            SmartPlaylistRule.Comparison(field, operator, textValue = text))

    @Test fun localAndRemoteDirectoriesAndComposersStayInTheirOwnAccount() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            val home = "local-mediastore"
            val work = "opensubsonic:work"
            dao.upsertRecording(RecordingEntity("rec:one", "Shared", "Artist", 125_000))
            dao.upsertRecording(RecordingEntity("rec:empty", "Empty composer", "Artist", 125_000))
            dao.upsertRecording(RecordingEntity("rec:unknown", "Unknown", "Artist", 125_000))
            dao.upsertMediaAsset(MediaAssetEntity("asset:home", "rec:one", home, "home1",
                "content://media/external/audio/media/1", "/Music/Trip Hop/01.flac", null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:work", "rec:one", work, "work1",
                "opensubsonic://work/1", "Music\\Jazz\\01.flac", null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:empty", "rec:empty", home, "home2",
                "content://media/external/audio/media/2", null, null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:unknown", "rec:unknown", work, "work3",
                "opensubsonic://work/3", null, null, null, null))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity("asset:home", null, null,
                composersJson = "[\"Alice\",\"Bob\"]"))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity("asset:work", null, null,
                composersJson = "[\"Joe\"]"))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity("asset:empty", null, null,
                composersJson = "[]"))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity("asset:unknown", null, null))
            val reader = CanonicalContextualFilterLibrary(dao)
            assertEquals(listOf("rec:one"), reader.songs(home, filter(
                SmartPlaylistRuleField.FOLDER_PATH, SmartPlaylistRuleOperator.EQUAL,
                "/music/trip hop")).map { it.recordingId })
            assertEquals(listOf("rec:one"), reader.songs(work, filter(
                SmartPlaylistRuleField.FOLDER_PATH, SmartPlaylistRuleOperator.EQUAL,
                "Music/Jazz")).map { it.recordingId })
            assertTrue(reader.songs(home, filter(
                SmartPlaylistRuleField.FOLDER_PATH, SmartPlaylistRuleOperator.EQUAL,
                "Music/Jazz")).isEmpty())
            assertEquals(listOf("rec:one"), reader.songs(home, filter(
                SmartPlaylistRuleField.COMPOSER, SmartPlaylistRuleOperator.EQUAL,
                "bob")).map { it.recordingId })
            assertTrue(reader.songs(home, filter(
                SmartPlaylistRuleField.COMPOSER, SmartPlaylistRuleOperator.EQUAL,
                "joe")).isEmpty())
            assertEquals(listOf("rec:empty"), reader.songs(home, filter(
                SmartPlaylistRuleField.COMPOSER, SmartPlaylistRuleOperator.IS_MISSING))
                    .map { it.recordingId })
            // Missing path in one MediaAsset is UNKNOWN, not a verified missing folder.
            assertTrue(reader.songs(home, filter(
                SmartPlaylistRuleField.FOLDER_PATH, SmartPlaylistRuleOperator.IS_MISSING))
                    .isEmpty())
            val scoped = CanonicalLibraryAvailabilityRepository(
                RoomCanonicalLibraryAvailabilityStore(dao),
                CanonicalLibraryProviderConfigurationReader { setOf(home) },
            )
            assertEquals(listOf("Alice", "Bob"), scoped.availableRecordingTags(
                listOf("rec:one")).single().composers)
            assertEquals(listOf("/Music/Trip Hop"), scoped.availableRecordingTags(
                listOf("rec:one")).single().folderPaths)
            val combined = CanonicalLibraryAvailabilityRepository(
                RoomCanonicalLibraryAvailabilityStore(dao),
                CanonicalLibraryProviderConfigurationReader { setOf(home, work) },
            ).availableRecordingTags(listOf("rec:one")).single()
            assertEquals(listOf("Alice", "Bob", "Joe"), combined.composers)
            assertEquals(listOf("/Music/Trip Hop", "Music/Jazz"), combined.folderPaths)
        } finally {
            db.close()
        }
    }
}
