package com.music.bitchord.ui.screens

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalMetadataPlaylistCreateTest {
    private val first = CatalogMetadataRecordingLabel(
        "123e4567-e89b-12d3-a456-426614174000", "Faixa 1", "Artista",
    )
    private val second = CatalogMetadataRecordingLabel(
        "223e4567-e89b-12d3-a456-426614174000", "Faixa 2", "Artista",
    )

    @Test fun newPlaylistStoresOnlyVerifiedRecordingIdsInOriginalOrder() {
        val planned = planCanonicalMetadataPlaylistCreation(
            "  Minha playlist  ", listOf(first, second, first), "playlist-id",
        )
        assertEquals("Minha playlist", planned?.name)
        assertEquals("playlist-id", planned?.id)
        assertEquals(listOf(first.recordingId, second.recordingId), planned?.recordingIds)
        assertEquals(null, planned?.sourceProviderKey)
    }

    @Test fun invalidIdentityOrNameCannotProduceAPlaylist() {
        assertNull(planCanonicalMetadataPlaylistCreation("", listOf(first), "id"))
        assertNull(planCanonicalMetadataPlaylistCreation("nome", emptyList(), "id"))
        assertNull(planCanonicalMetadataPlaylistCreation(
            "nome", listOf(CatalogMetadataRecordingLabel("spotify-id", "Faixa", "Artista")), "id",
        ))
        assertNull(planCanonicalMetadataPlaylistCreation("nome", listOf(first), ""))
    }

    @Test fun metadataMustBeStoredBeforeNewPlaylistAndFailureDoesNotWrite() = runBlocking {
        val calls = mutableListOf<String>()
        val failed = persistCanonicalMetadataPlaylist(
            "Nome", listOf(first), "playlist-id",
            rememberLabels = { calls += "labels-failed"; false },
            savePlaylist = { calls += "playlist" },
        )
        assertNull(failed)
        assertEquals(listOf("labels-failed"), calls)

        calls.clear()
        val result = persistCanonicalMetadataPlaylist(
            "Nome", listOf(first, second), "playlist-id",
            rememberLabels = { calls += "labels"; true },
            savePlaylist = {
                calls += "playlist"
                assertEquals(listOf(first.recordingId, second.recordingId), it.recordingIds)
            },
        )
        assertEquals("playlist-id", result?.id)
        assertEquals(listOf("labels", "playlist"), calls)
    }
    @Test fun verifiedMetadataSurvivesActualRoomRoundTripAndLabelPersistence() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(
            "rea-metadata-playlist-creation-test", Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        val labels = CatalogMetadataRecordingLabelRepository(preferences)
        val db = Room.inMemoryDatabaseBuilder(
            context, SmartPlaylistDefinitionDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repository = CanonicalStaticPlaylistRepository(db.staticPlaylistDao())
            val saved = persistCanonicalMetadataPlaylist(
                title = "Minha lista", labels = listOf(first, second, first),
                id = "verified-metadata-playlist",
                rememberLabels = labels::rememberLabels,
                savePlaylist = repository::save,
            )
            assertEquals("verified-metadata-playlist", saved?.id)
            assertEquals(listOf(first, second), labels.labels.value)
            val reopened = repository.list().single()
            assertEquals("Minha lista", reopened.name)
            assertEquals(listOf(first.recordingId, second.recordingId), reopened.recordingIds)
            assertNull(reopened.sourceProviderKey)

            val rejected = persistCanonicalMetadataPlaylist(
                title = "Falha", labels = listOf(first), id = "rejected",
                rememberLabels = { false },
                savePlaylist = repository::save,
            )
            assertNull(rejected)
            assertEquals(listOf("verified-metadata-playlist"), repository.list().map { it.id })
        } finally {
            db.close()
            preferences.edit().clear().commit()
        }
    }

}
