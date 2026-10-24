package com.music.bitchord.data.listening

import android.app.Application
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.RecordingEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Device regression #168: My Ratings remote 5/5 must be eligible for saved rules. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SmartPlaylistRemoteRatingEligibilityTest {
    private val query = RuleBasedSmartPlaylistQuery(minimumExplicitRating = 5.0)

    private suspend fun runWithCatalog(
        remote: RemoteSmartPlaylistRatingReader,
        block: suspend (CanonicalRuleBasedSmartPlaylistPreview, RoomRecordingRatingStore) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            for (id in listOf("rec:five", "rec:four", "rec:starred")) {
                dao.upsertRecording(RecordingEntity(id, id, "Artist", 180_000L))
            }
            val available = CanonicalLibraryAvailabilityReader {
                listOf("rec:five", "rec:four", "rec:starred", "rec:five")
            }
            val local = RoomRecordingRatingStore(listening.recordingRatingDao()) { 1234L }
            val preview = CanonicalRuleBasedSmartPlaylistPreview(
                availability = available,
                catalog = dao,
                ratings = local,
                listening = RoomListeningEventStore(listening.listeningEventDao()),
                remoteRatings = remote,
            )
            block(preview, local)
        } finally {
            listening.close()
            catalog.close()
        }
    }

    @Test fun numericFiveFromCompleteAccountScopedRemoteSnapshotIsEligibleWithoutLocalWrite() = runTest {
        val remote = RemoteSmartPlaylistRatingReader {
            RemoteSmartPlaylistRatingSnapshot(
                complete = true,
                evidence = listOf(
                    RemoteSmartPlaylistRatingEvidence("rec:five", RemoteSmartPlaylistRatingKind.VALUE, 5),
                    RemoteSmartPlaylistRatingEvidence("rec:four", RemoteSmartPlaylistRatingKind.VALUE, 4),
                    RemoteSmartPlaylistRatingEvidence("rec:starred", RemoteSmartPlaylistRatingKind.NOT_PROVIDED),
                ),
            )
        }
        runWithCatalog(remote) { preview, local ->
            assertEquals(listOf("rec:five"), preview.preview(query).map { it.recordingId })
            assertNull(local.currentRating("rec:five"))
            assertEquals(emptyList<RecordingRatingChange>(), local.ratingHistory("rec:five"))
        }
    }

    @Test fun disagreementBetweenLocalAndRemoteDoesNotSilentlyPromoteToFive() = runTest {
        val remote = RemoteSmartPlaylistRatingReader {
            RemoteSmartPlaylistRatingSnapshot(true, listOf(
                RemoteSmartPlaylistRatingEvidence("rec:five", RemoteSmartPlaylistRatingKind.VALUE, 5),
            ))
        }
        runWithCatalog(remote) { preview, local ->
            local.setRecordingRating("rec:five", 4.5)
            assertEquals(emptyList<String>(), preview.preview(query).map { it.recordingId })
            assertEquals(4.5, local.currentRating("rec:five")!!.value, 0.0)
        }
    }

    @Test fun conflictingRemoteAssetValuesAndClearedRatingFailClosed() = runTest {
        var evidence = listOf(
            RemoteSmartPlaylistRatingEvidence("rec:five", RemoteSmartPlaylistRatingKind.VALUE, 5),
            RemoteSmartPlaylistRatingEvidence("rec:five", RemoteSmartPlaylistRatingKind.VALUE, 4),
        )
        val remote = RemoteSmartPlaylistRatingReader {
            RemoteSmartPlaylistRatingSnapshot(true, evidence)
        }
        runWithCatalog(remote) { preview, _ ->
            assertEquals(emptyList<String>(), preview.preview(query).map { it.recordingId })
            evidence = listOf(
                RemoteSmartPlaylistRatingEvidence("rec:five", RemoteSmartPlaylistRatingKind.VALUE, 5),
                RemoteSmartPlaylistRatingEvidence("rec:five", RemoteSmartPlaylistRatingKind.CLEARED),
            )
            assertEquals(emptyList<String>(), preview.preview(query).map { it.recordingId })
            evidence = listOf(
                RemoteSmartPlaylistRatingEvidence("rec:five", RemoteSmartPlaylistRatingKind.INVALID),
            )
            assertEquals(emptyList<String>(), preview.preview(query).map { it.recordingId })
        }
    }

    @Test fun changingAccountOrLoggingOutRevokesCachedRemoteButKeepsLocalRules() = runTest {
        var currentScopeComplete = true
        val remote = RemoteSmartPlaylistRatingReader {
            RemoteSmartPlaylistRatingSnapshot(
                complete = currentScopeComplete,
                evidence = listOf(
                    RemoteSmartPlaylistRatingEvidence("rec:five", RemoteSmartPlaylistRatingKind.VALUE, 5),
                ),
            )
        }
        runWithCatalog(remote) { preview, local ->
            assertEquals(listOf("rec:five"), preview.preview(query).map { it.recordingId })
            currentScopeComplete = false
            assertEquals(emptyList<String>(), preview.preview(query).map { it.recordingId })
            local.setRecordingRating("rec:four", 5.0)
            assertEquals(listOf("rec:four"), preview.preview(query).map { it.recordingId })
        }
    }
}
