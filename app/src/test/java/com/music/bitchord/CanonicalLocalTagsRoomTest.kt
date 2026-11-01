package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.MediaAssetTagsEntity
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityRepository
import com.music.bitchord.data.catalog.CanonicalLibraryProviderConfigurationReader
import com.music.bitchord.data.catalog.RoomCanonicalLibraryAvailabilityStore
import com.music.bitchord.data.listening.CanonicalContextualFilterLibrary
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Filter
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/** Tags for local assets and remote assets follow the same canonical contract. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CanonicalLocalTagsRoomTest {
    private val local = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
    private fun song(id: String, genres: List<String>?, moods: List<String>? = null) = Song(
        videoId = "content://media/external/audio/media/$id",
        title = "Local $id",
        artist = "Artist",
        thumbnailUrl = null,
        durationText = "3:00",
        localUri = "content://media/external/audio/media/$id",
        localDateAddedSeconds = 1_700_000_000L,
        localGenres = genres,
        localMoods = moods,
    )
    private fun filter(field: SmartPlaylistRuleField,
                       operator: SmartPlaylistRuleOperator, text: String? = null) =
        SavedSmartPlaylistV2Filter("tag-filter", "Tags",
            SmartPlaylistRule.Comparison(field, operator, textValue = text))

    @Test fun localMetadataSurvivesCanonicalSyncAndUsesSameGenreMoodRulesAsServer() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val tagged = song("101", listOf("Dream Pop", "K-Pop"), listOf("Calm"))
            val empty = song("102", emptyList(), emptyList())
            val unknown = song("103", null)
            CanonicalLocalLibrary.syncWithDatabase(
                context, database, listOf(tagged, empty, unknown),
            )
            val dao = database.catalogDao()
            val assets = listOf("101", "102", "103").associateWith { id ->
                requireNotNull(dao.mediaAsset(local, "content://media/external/audio/media/$id"))
            }
            val scoped = dao.providerScopedTagRows(
                local, assets.values.map { it.recordingId },
            ).associateBy { it.recordingId }
            assertEquals("[\"Dream Pop\",\"K-Pop\"]", scoped[assets.getValue("101").recordingId]?.genresJson)
            assertEquals("[\"Calm\"]", scoped[assets.getValue("101").recordingId]?.moodsJson)
            assertEquals("[]", scoped[assets.getValue("102").recordingId]?.genresJson)
            assertNull(scoped[assets.getValue("103").recordingId]?.genresJson)

            val reader = CanonicalContextualFilterLibrary(dao)
            val pop = filter(SmartPlaylistRuleField.GENRE,
                SmartPlaylistRuleOperator.EQUAL, "dream pop")
            assertEquals(listOf("Local 101"),
                reader.songs(local, pop).map { it.title })
            val calm = filter(SmartPlaylistRuleField.MOOD,
                SmartPlaylistRuleOperator.EQUAL, "calm")
            assertEquals(listOf("Local 101"),
                reader.songs(local, calm).map { it.title })
            val missing = filter(SmartPlaylistRuleField.GENRE,
                SmartPlaylistRuleOperator.IS_MISSING)
            assertEquals(listOf("Local 102"),
                reader.songs(local, missing).map { it.title })
            val global = CanonicalLibraryAvailabilityRepository(
                RoomCanonicalLibraryAvailabilityStore(dao),
                CanonicalLibraryProviderConfigurationReader { setOf(local) },
            )
            val globalGenres = global.availableRecordingTags(
                assets.values.map { it.recordingId },
            ).associateBy { it.recordingId }
            assertEquals(listOf("Dream Pop", "K-Pop"),
                globalGenres[assets.getValue("101").recordingId]?.genres)
            assertTrue(globalGenres[assets.getValue("103").recordingId]?.genreEvidenceUnknown == true)

            // Same canonical Recording, different independent MediaAsset owner.
            // Local rules and account selection must never inherit remote Jazz.
            val sameRecording = assets.getValue("101").recordingId
            val remote = "opensubsonic:work"
            dao.upsertMediaAsset(MediaAssetEntity("remote:101", sameRecording, remote,
                "native:101", "opensubsonic://asset/work", null, null, null, null))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity(
                "remote:101", "[\"Jazz\"]", "[]",
            ))
            val jazz = filter(SmartPlaylistRuleField.GENRE,
                SmartPlaylistRuleOperator.EQUAL, "jazz")
            assertTrue(reader.songs(local, jazz).isEmpty())
            assertEquals(listOf("Local 101"), reader.songs(remote, jazz).map { it.title })
            assertEquals(listOf("Dream Pop", "K-Pop"),
                global.availableRecordingTags(listOf(sameRecording)).single().genres)
            val bothSources = CanonicalLibraryAvailabilityRepository(
                RoomCanonicalLibraryAvailabilityStore(dao),
                CanonicalLibraryProviderConfigurationReader { setOf(local, remote) },
            )
            assertEquals(listOf("Dream Pop", "K-Pop", "Jazz"),
                bothSources.availableRecordingTags(listOf(sameRecording)).single().genres)

            // An actual rescan replaces the local owner's evidence, never a remote asset's.
            CanonicalLocalLibrary.syncWithDatabase(context, database, listOf(
                tagged.copy(localGenres = emptyList(), localMoods = null), empty, unknown,
            ))
            val after = dao.providerScopedTagRows(local,
                assets.values.map { it.recordingId }).associateBy { it.recordingId }
            assertEquals("[]", after[assets.getValue("101").recordingId]?.genresJson)
            assertNull(after[assets.getValue("101").recordingId]?.moodsJson)
            assertTrue(reader.songs(local, pop).isEmpty())
            assertEquals(listOf("Local 101"), reader.songs(remote, jazz).map { it.title })
        } finally { database.close() }
    }
}
