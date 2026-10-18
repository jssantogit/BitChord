package com.music.bitchord.data.catalog

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalRecordingArtistGroupingTest {
    private lateinit var context: Context
    private var database: CanonicalCatalogDatabase? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun `empty input returns empty without querying store`() = runTest {
        val store = CountingGroupingStore()
        val reader = CanonicalRecordingArtistGroupingRepository(store)

        assertEquals(emptyMap<String, List<String>>(), reader.artistIdsByRecording(emptyList()))
        assertEquals(0, store.queryCount)
    }

    @Test
    fun `bulk grouping chunks large inputs instead of querying per recording`() = runTest {
        val store = CountingGroupingStore { ids ->
            ids.asReversed().mapIndexed { index, id ->
                CanonicalRecordingArtistIdRow(id, "artist:$id", index)
            }
        }
        val reader = CanonicalRecordingArtistGroupingRepository(store)
        val ids = (0 until 1_200).map { "recording:$it" }

        val result = reader.artistIdsByRecording(ids)

        assertEquals(1_200, result.size)
        assertEquals(listOf(500, 500, 200), store.batchSizes)
        assertEquals(3, store.queryCount)
        assertEquals(listOf("artist:recording:0"), result.getValue("recording:0"))
        assertEquals(listOf("artist:recording:1199"), result.getValue("recording:1199"))
        assertEquals(result, reader.artistIdsByRecording(ids))
        assertEquals(6, store.queryCount)
    }

    @Test
    fun `real Room seam returns ordered canonical artist ids only and excludes enrichment evidence`() = runTest {
        val dao = openDatabase().catalogDao()
        val artists = listOf(
            ArtistEntity("artist:a", "Same Name"),
            ArtistEntity("artist:b", "Artist B"),
            ArtistEntity("artist:same-name-other-id", "Same Name"),
        )
        artists.forEach { dao.upsertArtist(it) }
        listOf("recording:collab", "recording:a", "recording:same-name", "recording:mb-only", "recording:none")
            .forEach { id -> dao.upsertRecording(recording(id, artistDisplay = "Same Name")) }

        dao.upsertRecordingArtistCredits(
            listOf(
                RecordingArtistCreditEntity("recording:collab", 0, "artist:a"),
                RecordingArtistCreditEntity("recording:collab", 1, "artist:b"),
                RecordingArtistCreditEntity("recording:collab", 2, "artist:a"),
                RecordingArtistCreditEntity("recording:a", 0, "artist:a"),
                RecordingArtistCreditEntity("recording:same-name", 0, "artist:same-name-other-id"),
            ),
        )
        dao.upsertMusicBrainzRecordingArtistCredits(
            listOf(
                MusicBrainzRecordingArtistCreditEntity(
                    recordingId = "recording:mb-only",
                    position = 0,
                    musicBrainzArtistId = "mbid-artist-a",
                    name = "Same Name",
                    joinPhrase = null,
                ),
            ),
        )

        val reader = CanonicalRecordingArtistGroupingRepository(
            RoomCanonicalRecordingArtistGroupingStore(dao),
        )
        val result = reader.artistIdsByRecording(
            listOf("recording:collab", "recording:a", "recording:same-name", "recording:mb-only", "recording:none"),
        )

        assertEquals(listOf("artist:a", "artist:b"), result.getValue("recording:collab"))
        assertEquals(listOf("artist:a"), result.getValue("recording:a"))
        assertEquals(listOf("artist:same-name-other-id"), result.getValue("recording:same-name"))
        assertEquals(emptyList<String>(), result.getValue("recording:mb-only"))
        assertEquals(emptyList<String>(), result.getValue("recording:none"))
        assertEquals(
            result.getValue("recording:a").single(),
            result.getValue("recording:collab").first(),
        )
        assertTrue(
            result.getValue("recording:a").single() != result.getValue("recording:same-name").single(),
        )
    }

    @Test
    fun `grouping projection exposes only canonical ids and position with no provider or display metadata`() {
        val fields = CanonicalRecordingArtistIdRow::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
        val storeDependencyTypes = RoomCanonicalRecordingArtistGroupingStore::class.java.declaredFields
            .map { it.type.name }

        assertEquals(setOf("recordingId", "artistId", "position"), fields)
        assertTrue(fields.none { it in setOf("artistDisplay", "provider", "providerKey", "sourceKey", "mediaAssetId", "uri") })
        assertTrue(storeDependencyTypes.none { type ->
            type.contains("Transport", ignoreCase = true) ||
                type.contains("HttpClient", ignoreCase = true) ||
                type.contains("Provider", ignoreCase = true) ||
                type.contains("MediaAsset", ignoreCase = true)
        })
    }

    @Test
    fun `real Room grouping handles twelve hundred recording ids without bind overflow`() = runTest {
        val dao = openDatabase().catalogDao()
        dao.upsertArtist(ArtistEntity("artist:shared", "Shared Artist"))
        val ids = (0 until 1_200).map { "recording:large:$it" }
        ids.forEach { id -> dao.upsertRecording(recording(id, artistDisplay = "Display must not matter")) }
        dao.upsertRecordingArtistCredits(
            ids.map { id -> RecordingArtistCreditEntity(id, 0, "artist:shared") },
        )
        val reader = CanonicalRecordingArtistGroupingRepository(
            RoomCanonicalRecordingArtistGroupingStore(dao),
        )

        val first = reader.artistIdsByRecording(ids)
        val second = reader.artistIdsByRecording(ids)

        assertEquals(1_200, first.size)
        assertTrue(ids.all { id -> first[id] == listOf("artist:shared") })
        assertEquals(first, second)
    }

    private fun openDatabase(): CanonicalCatalogDatabase =
        Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }

    private fun recording(id: String, artistDisplay: String) = RecordingEntity(
        id = id,
        title = id,
        artistDisplay = artistDisplay,
        durationMs = 180_000L,
    )

    private class CountingGroupingStore(
        private val rows: (List<String>) -> List<CanonicalRecordingArtistIdRow> = { emptyList() },
    ) : CanonicalRecordingArtistGroupingStore {
        var queryCount = 0
            private set
        val batchSizes = mutableListOf<Int>()

        override suspend fun canonicalArtistCreditRows(
            recordingIds: List<String>,
        ): List<CanonicalRecordingArtistIdRow> {
            queryCount += 1
            batchSizes += recordingIds.size
            return rows(recordingIds)
        }
    }
}
