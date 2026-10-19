package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityRepository
import com.music.bitchord.data.catalog.CanonicalLibraryProviderConfigurationReader
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingRepository
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.RoomCanonicalLibraryAvailabilityStore
import com.music.bitchord.data.catalog.RoomCanonicalRecordingArtistGroupingStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
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
class LibraryRediscoveryPreviewIntegrationTest {
    private var catalogDb: CanonicalCatalogDatabase? = null
    private var listeningDb: ListeningEventDatabase? = null
    private val day = 24L * 60L * 60L * 1000L
    private val now = 100L * day

    @After fun tearDown() {
        catalogDb?.close()
        listeningDb?.close()
    }

    @Test fun realRoomPreviewUsesFullLibraryRankingAndNeverWrites() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        catalogDb = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        listeningDb = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        val dao = requireNotNull(catalogDb).catalogDao()
        val top = (0 until 6).map { "unplayed:$it" }
        val oldExplicit = "old-explicit"
        val oldBehavior = "old-behavior"
        val recentInteraction = "recent-interaction"
        val startOnly = "start-only"
        val negative = "negative"
        val unavailable = "unavailable"
        val available = top + listOf(oldExplicit, oldBehavior, recentInteraction, startOnly, negative)
        val all = available + unavailable

        all.forEach { id ->
            dao.upsertRecording(RecordingEntity(id = id, title = id, artistDisplay = "Various", durationMs = 180_000L))
        }
        available.forEach { id ->
            val uri = "content://media/external/audio/$id"
            dao.upsertMediaAsset(MediaAssetEntity(
                id = "asset:$id",
                recordingId = id,
                provider = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
                sourceKey = uri,
                uri = uri,
                path = null,
                dateAddedSeconds = null,
                dateModifiedSeconds = null,
                artworkUri = null,
            ))
        }

        val clock = MutableClock(now - 90L * day)
        val eventIds = GuardedIds()
        val eventStore = RoomListeningEventStore(
            dao = requireNotNull(listeningDb).listeningEventDao(),
            clock = clock,
            idGenerator = eventIds,
        )
        val ratingStore = RoomRecordingRatingStore(
            dao = requireNotNull(listeningDb).recordingRatingDao(),
            nowEpochMs = { clock.now },
        )
        top.forEach { ratingStore.setRecordingRating(it, 5.0) }
        mapOf(
            oldExplicit to 4.0,
            oldBehavior to 3.0,
            recentInteraction to 4.0,
            startOnly to 5.0,
            negative to 2.0,
            unavailable to 5.0,
        ).forEach { (id, rating) -> ratingStore.setRecordingRating(id, rating) }

        suspend fun start(id: String, daysAgo: Long, intent: SelectionIntent = SelectionIntent.AUTOMATIC) {
            clock.now = now - daysAgo * day
            eventStore.recordPlaybackStarted(
                PlaybackStart(recordingId = id, selectionIntent = intent, selectionOrigin = SelectionOrigin.LIBRARY),
            )
        }
        suspend fun finish(id: String, daysAgo: Long, intent: SelectionIntent = SelectionIntent.AUTOMATIC) {
            start(id, daysAgo, intent)
            clock.now += 1000L
            eventStore.recordPlaybackEnded(PlaybackEnd(
                recordingId = id,
                listenedDurationMs = 180_000L,
                recordingDurationMs = 180_000L,
                endCause = PlaybackEndCause.ENDED,
            ))
        }
        finish(oldBehavior, 80L, SelectionIntent.MANUAL)
        finish(oldBehavior, 79L)
        finish(unavailable, 78L)
        finish(negative, 70L)
        finish(recentInteraction, 50L)
        finish(oldExplicit, 45L)
        start(startOnly, 44L)
        start(recentInteraction, 2L)
        clock.now = now
        eventIds.allowWrites = false

        val availability = CanonicalLibraryAvailabilityRepository(
            RoomCanonicalLibraryAvailabilityStore(dao),
            CanonicalLibraryProviderConfigurationReader { setOf(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER) },
        )
        val source = LibraryRecommendationCandidateSource(availability)
        val ranker = PersonalRanker(eventStore, ratingStore, nowEpochMs = { clock.now })
        val session = SessionContextReranker(eventStore, nowEpochMs = { clock.now })
        val diversity = ArtistAwareDiversityReranker(
            CanonicalRecordingArtistGroupingRepository(RoomCanonicalRecordingArtistGroupingStore(dao)),
        )
        val pipeline = LibraryRecommendationPipeline(source, ranker, session, diversity)
        val planner = LibraryRediscoveryPreviewPlanner(nowEpochMs = { clock.now })

        val beforeAvailability = source.candidates()
        val beforeRatings = all.associateWith { ratingStore.currentRating(it) }
        val beforeHistory = all.associateWith { ratingStore.ratingHistory(it) }
        val beforeBehavior = all.associateWith { eventStore.behaviorSummary(it) }
        val beforeEvents = all.associateWith { eventStore.recentEventsForRecording(it, 50) }
        val beforeCatalogTables = tableNames(requireNotNull(catalogDb))
        val beforeListeningTables = tableNames(requireNotNull(listeningDb))
        val beforeSession = eventStore.currentSession(clock.now)

        val full = pipeline.rank() // No limit, including candidates below position five
        assertEquals(available.toSet(), full.map { it.base.base.recordingId }.toSet())
        assertTrue(full.indexOfFirst { it.base.base.recordingId == oldExplicit } >= 5)
        assertTrue(full.indexOfFirst { it.base.base.recordingId == oldBehavior } >= 5)

        val preview = planner.preview(full)
        val presentation = LibraryRediscoveryPresentationContract().present(preview)
        assertEquals(listOf(oldBehavior, oldExplicit), presentation.map { it.recordingId })
        assertEquals(listOf(1, 2), presentation.map { it.shelfPosition })
        assertEquals(preview.map { it.rankPosition }, presentation.map { it.rankPosition })
        assertEquals(preview.map { it.lastAudibleListenAtEpochMs },
            presentation.map { it.lastAudibleListenAtEpochMs })
        assertEquals(listOf(79L, 45L), presentation.map { it.daysSinceLastAudible })
        assertTrue(presentation.all { it.availability ==
            RediscoveryPresentationAvailability.AVAILABLE_AT_SNAPSHOT &&
            it.canNavigateToCanonicalRecording })
        assertEquals(emptyList<RediscoveryPresentationItem>(),
            LibraryRediscoveryPresentationContract().present(
                planner.preview(pipeline.rank(limit = 5))))
        assertEquals(listOf(oldBehavior, oldExplicit), preview.map { it.recordingId })
        assertEquals(listOf(79L * day - 1000L, 45L * day - 1000L),
            preview.map { it.timeSinceLastAudibleMs })
        assertEquals(
            listOf(
                setOf(RediscoveryReason.CONFIRMED_POSITIVE_BEHAVIOR, RediscoveryReason.LONG_ABSENCE),
                setOf(RediscoveryReason.POSITIVE_EXPLICIT_PREFERENCE, RediscoveryReason.LONG_ABSENCE),
            ),
            preview.map { it.reasons },
        )
        preview.forEach { assertSame(full[it.rankPosition - 1], it.source) }
        assertTrue(planner.preview(pipeline.rank(limit = 5)).isEmpty())
        assertEquals(preview, planner.preview(full))
        assertEquals(full, pipeline.rank())
        assertFalse(preview.any { it.recordingId in setOf(unavailable, negative, startOnly, recentInteraction) })

        assertEquals(beforeAvailability, source.candidates())
        assertEquals(beforeRatings, all.associateWith { ratingStore.currentRating(it) })
        assertEquals(beforeHistory, all.associateWith { ratingStore.ratingHistory(it) })
        assertEquals(beforeBehavior, all.associateWith { eventStore.behaviorSummary(it) })
        assertEquals(beforeEvents, all.associateWith { eventStore.recentEventsForRecording(it, 50) })
        assertEquals(beforeCatalogTables, tableNames(requireNotNull(catalogDb)))
        assertEquals(beforeListeningTables, tableNames(requireNotNull(listeningDb)))
        assertEquals(beforeSession, eventStore.currentSession(clock.now))
        assertNull(beforeSession)
    }

    private fun tableNames(db: androidx.room.RoomDatabase): Set<String> =
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private class MutableClock(var now: Long) : ListeningClock {
        override fun nowEpochMs(): Long = now
    }

    private class GuardedIds : ListeningIdGenerator {
        var allowWrites = true
        private var next = 0
        override fun nextId(): String {
            check(allowWrites) { "Rediscovery must not write Listening events" }
            next++
            return "rediscovery-fixture-$next"
        }
    }
}
