package com.music.bitchord.ui.screens

import android.app.Application
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The preview may be re-evaluated only after the source Room tables invalidate.
 * These tests exercise real Room invalidations, not artificial callback fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SmartPlaylistEvidenceInvalidationsTest {
    @Test fun catalogRecordingWriteInvalidatesAnAlreadyVisiblePreview() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val ready = CompletableDeferred<Unit>()
            val changes = async(Dispatchers.Default) {
                withTimeout(10_000) {
                    var emissions = 0
                    smartPlaylistEvidenceChanges(catalog, listening)
                        .onEach { if (++emissions == 1) ready.complete(Unit) }
                        .take(2).toList()
                }
            }
            withTimeout(10_000) { ready.await() }
            catalog.catalogDao().upsertRecording(RecordingEntity(
                "rec:change", "Updated", "Artist", 180_000L,
            ))
            assertEquals(2, changes.await().size)
        } finally {
            catalog.close()
            listening.close()
        }
    }

    @Test fun listeningRatingWriteInvalidatesAnAlreadyVisiblePreview() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val ready = CompletableDeferred<Unit>()
            val changes = async(Dispatchers.Default) {
                withTimeout(10_000) {
                    var emissions = 0
                    smartPlaylistEvidenceChanges(catalog, listening)
                        .onEach { if (++emissions == 1) ready.complete(Unit) }
                        .take(2).toList()
                }
            }
            withTimeout(10_000) { ready.await() }
            RoomRecordingRatingStore(listening.recordingRatingDao())
                .setRecordingRating("rec:changed-rating", 4.0)
            assertEquals(2, changes.await().size)
        } finally {
            catalog.close()
            listening.close()
        }
    }
}
