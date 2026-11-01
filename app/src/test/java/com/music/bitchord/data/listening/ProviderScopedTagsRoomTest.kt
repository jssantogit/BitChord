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
                "asset:home", "[\"Dream Pop\",\"K-Pop\"]", "[\"Calm\"]",
                bpmsJson = "[128.0]", stylesJson = "[\"Shoegaze\"]"))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity("asset:work", "[\"Jazz\"]", "[]",
                bpmsJson = "[]", stylesJson = "[]"))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity("asset:empty", "[]", "[]",
                bpmsJson = "[]", stylesJson = "[]"))
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
            val style = filter(SmartPlaylistRuleField.STYLE,
                SmartPlaylistRuleOperator.EQUAL, "shoegaze")
            assertEquals(listOf("rec:shared"), reader.songs(home, style).map { it.recordingId })
            assertTrue(reader.songs(work, style).isEmpty())
            val bpm = SavedSmartPlaylistV2Filter("bpm", "Fast tempo",
                SmartPlaylistRule.Comparison(SmartPlaylistRuleField.BPM,
                    SmartPlaylistRuleOperator.GREATER_OR_EQUAL, value = 120.0))
            assertEquals(listOf("rec:shared"), reader.songs(home, bpm).map { it.recordingId })
            assertTrue(reader.songs(work, bpm).isEmpty())
            val missingBpm = bpm.copy(rules = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.BPM, SmartPlaylistRuleOperator.IS_MISSING))
            assertEquals(listOf("rec:empty"), reader.songs(home, missingBpm).map { it.recordingId })
            assertEquals(listOf("rec:shared"), reader.songs(work, missingBpm).map { it.recordingId })
            // Global smart-playlist previews receive only RecordingIds and
            // aggregated tags from configured accounts, never native source IDs.
            val global = CanonicalLibraryAvailabilityRepository(
                RoomCanonicalLibraryAvailabilityStore(dao),
                CanonicalLibraryProviderConfigurationReader { setOf(home) },
            )
            val availableTags = global.availableRecordingTags(
                listOf("rec:shared", "rec:empty", "rec:unknown"),
            ).associateBy { it.recordingId }
            assertEquals(listOf("Dream Pop", "K-Pop"), availableTags["rec:shared"]?.genres)
            assertEquals(false, availableTags["rec:shared"]?.genreEvidenceUnknown)
            assertEquals(listOf(128.0), availableTags["rec:shared"]?.bpms)
            assertEquals(listOf("Shoegaze"), availableTags["rec:shared"]?.styles)
            assertEquals(false, availableTags["rec:shared"]?.bpmEvidenceUnknown)
            assertEquals(false, availableTags["rec:shared"]?.styleEvidenceUnknown)
            assertEquals(true, availableTags["rec:unknown"]?.bpmEvidenceUnknown)
            assertEquals(emptyList<String>(), availableTags["rec:empty"]?.genres)
            assertEquals(false, availableTags["rec:empty"]?.genreEvidenceUnknown)
            assertEquals(true, availableTags["rec:unknown"]?.genreEvidenceUnknown)
            val withTwoAccounts = CanonicalLibraryAvailabilityRepository(
                RoomCanonicalLibraryAvailabilityStore(dao),
                CanonicalLibraryProviderConfigurationReader { setOf(home, work) },
            )
            assertEquals(listOf("Dream Pop", "K-Pop", "Jazz"),
                withTwoAccounts.availableRecordingTags(listOf("rec:shared")).single().genres)
            dao.deleteAllMediaAssets(home)
            assertTrue(dao.providerScopedTagRows(home, listOf("rec:shared")).isEmpty())
            assertEquals(listOf("rec:shared"), reader.songs(work, jazz).map { it.recordingId })
        } finally {
            db.close()
        }
    }
}
