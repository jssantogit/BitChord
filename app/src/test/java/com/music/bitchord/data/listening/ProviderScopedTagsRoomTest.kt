package com.music.bitchord.data.listening

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
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

/** Real Room proof: provider tags never become global Recording metadata. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class ProviderScopedTagsRoomTest {
    @Test fun tagsAndExplicitlyEmptyFieldsWorkWithinOneProviderOnly() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            val home = "opensubsonic:home"
            val work = "opensubsonic:work"
            dao.upsertRecording(RecordingEntity("rec:shared", "Shared", "Artist", 120_000L))
            dao.upsertRecording(RecordingEntity("rec:empty", "Untagged", "Artist", 120_000L))
            dao.upsertRecording(RecordingEntity("rec:unknown", "Unreported", "Artist", 120_000L))
            dao.upsertMediaAsset(MediaAssetEntity("asset:home", "rec:shared", home,
                "remote-1", "opensubsonic://home/1", null, null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:work", "rec:shared", work,
                "remote-1", "opensubsonic://work/1", null, null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:empty", "rec:empty", home,
                "remote-2", "opensubsonic://home/2", null, null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:unknown", "rec:unknown", home,
                "remote-3", "opensubsonic://home/3", null, null, null, null))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity(
                "asset:home", "[\"Dream Pop\",\"K-Pop\"]", "[\"Calm\"]"))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity("asset:work", "[\"Jazz\"]", "[]"))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity("asset:empty", "[]", "[]"))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity("asset:unknown", null, null))
            val reader = CanonicalContextualFilterLibrary(dao)
            fun filter(field: SmartPlaylistRuleField, operator: SmartPlaylistRuleOperator,
                       text: String? = null) = SavedSmartPlaylistV2Filter(
                id = "f", name = "Filter",
                rules = SmartPlaylistRule.Comparison(field, operator, textValue = text),
            )
            val pop = filter(SmartPlaylistRuleField.GENRE,
                SmartPlaylistRuleOperator.EQUAL, "dream pop")
            assertEquals(listOf("rec:shared"), reader.songs(home, pop).map { it.recordingId })
            assertTrue(reader.songs(work, pop).isEmpty())
            val jazz = pop.copy(rules = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.GENRE, SmartPlaylistRuleOperator.EQUAL,
                textValue = "jazz"))
            assertEquals(listOf("rec:shared"), reader.songs(work, jazz).map { it.recordingId })
            assertTrue(reader.songs(home, jazz).isEmpty())
            val missing = filter(SmartPlaylistRuleField.GENRE,
                SmartPlaylistRuleOperator.IS_MISSING)
            assertEquals(listOf("rec:empty"), reader.songs(home, missing).map { it.recordingId })
            assertTrue(reader.songs(work, missing).isEmpty())
            val calm = filter(SmartPlaylistRuleField.MOOD,
                SmartPlaylistRuleOperator.EQUAL, "calm")
            assertEquals(listOf("rec:shared"), reader.songs(home, calm).map { it.recordingId })
            assertTrue(reader.songs(work, calm).isEmpty())
            dao.deleteAllMediaAssets(home)
            assertTrue(dao.providerScopedTagRows(home, listOf("rec:shared")).isEmpty())
            assertEquals(listOf("rec:shared"), reader.songs(work, jazz).map { it.recordingId })
        } finally {
            db.close()
        }
    }
}
