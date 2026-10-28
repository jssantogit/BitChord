package com.music.bitchord.data.listening

import android.app.Application
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibrarySourceKind
import com.music.bitchord.data.catalog.CanonicalAvailableRecordingSources
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.RecordingEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
class SmartPlaylistBatchEvidenceRoomTest {
    @Test fun realRoomBatchPreservesAudibleCompletionAndOneStarRating() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            catalog.catalogDao().upsertRecording(RecordingEntity("rec:low", "Low", "Artist", 250_000L))
            catalog.catalogDao().upsertRecording(RecordingEntity("rec:unrated", "Unrated", "Artist", null))
            val clock = ListeningClock { 1_000_000L }
            var sequence = 0
            val store = RoomListeningEventStore(
                listening.listeningEventDao(), clock = clock,
                idGenerator = ListeningIdGenerator { "event-" + (++sequence) },
            )
            val ratings = RoomRecordingRatingStore(listening.recordingRatingDao())
            ratings.setRecordingRating("rec:low", 1.0)
            store.recordPlaybackStarted(PlaybackStart(recordingId = "rec:low"))
            store.recordPlaybackEnded(PlaybackEnd(
                recordingId = "rec:low", listenedDurationMs = 250_000L,
                recordingDurationMs = 250_000L, endCause = PlaybackEndCause.ENDED,
            ))
            store.recordPlaybackStarted(PlaybackStart(recordingId = "rec:unrated"))
            val reader = RoomSmartPlaylistBatchEvidenceReader(
                catalog.catalogDao(), listening.listeningEventDao(), listening.recordingRatingDao(),
            )
            val input = reader.read(listOf("rec:unrated", "rec:low", "rec:missing"))
            assertEquals(2, input.size)
            val low = input.single { it.recordingId == "rec:low" }
            assertEquals(1.0, low.explicitRating)
            assertEquals(1L, low.audibleCompletions)
            assertEquals(1_000_000L, low.lastAudibleAtEpochMs)
            assertEquals(250_000L, low.durationMs)
            val unrated = input.single { it.recordingId == "rec:unrated" }
            assertNull(unrated.explicitRating)
            assertEquals(0L, unrated.audibleCompletions)
            assertNull(unrated.lastAudibleAtEpochMs)
        } finally {
            catalog.close()
            listening.close()
        }
    }

    @Test fun productionLikeBatchFeedsBothV1AndV2BeyondFiveThousandIds() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val ids = (0 until 5_001).map { "rec:" + it.toString().padStart(5, '0') }
            var batchCalls = 0
            val reader = SmartPlaylistBatchEvidenceReader { incoming ->
                batchCalls++
                assertTrue(incoming.size <= 400)
                incoming.map { id ->
                    RuleBasedRecordingCandidate(id, 1.0, 1L, 100L, 240_000L, true)
                }
            }
            val available = object : CanonicalLibraryAvailabilityReader {
                override suspend fun availableRecordingIds(): List<String> = ids
            }
            val preview = CanonicalRuleBasedSmartPlaylistPreview(
                availability = available,
                catalog = catalog.catalogDao(),
                ratings = RoomRecordingRatingStore(listening.recordingRatingDao()),
                listening = RoomListeningEventStore(listening.listeningEventDao()),
                planner = RuleBasedSmartPlaylistPlanner(nowEpochMs = { 500L }),
                batchReader = reader,
                v2Planner = SmartPlaylistV2RuleEngine(nowEpochMs = { 500L }),
            )
            val legacy = preview.preview(RuleBasedSmartPlaylistQuery(
                minimumDurationMs = 200_000L, limit = 100,
            ))
            assertEquals(100, legacy.size)
            assertEquals(13, batchCalls)
            val modern = preview.preview(SmartPlaylistV2Query(
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                    SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0,
                ), limit = 200,
            ))
            assertEquals(200, modern.size)
            assertEquals(26, batchCalls)
        } finally {
            catalog.close()
            listening.close()
        }
    }
    @Test fun catalogBatchReadsActualRecordingTextWithoutInventingForMissingId() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            catalog.catalogDao().upsertRecording(
                RecordingEntity("rec:one", "Super Shy", "NewJeans", 150_000L),
            )
            val batch = RoomSmartPlaylistBatchEvidenceReader(
                catalog.catalogDao(), listening.listeningEventDao(), listening.recordingRatingDao(),
            ).read(listOf("rec:missing", "rec:one"))
            assertEquals(1, batch.size)
            assertEquals("Super Shy", batch.single().title)
            assertEquals("NewJeans", batch.single().artistDisplay)
            val filter = SmartPlaylistV2Query(
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.ARTIST_DISPLAY,
                    SmartPlaylistRuleOperator.CONTAINS,
                    textValue = "newjeans",
                ),
            )
            assertEquals(listOf("rec:one"), SmartPlaylistV2RuleEngine().preview(
                batch, filter,
            ).map { it.recordingId })
        } finally {
            catalog.close()
            listening.close()
        }
    }

    @Test fun realRoomDistinguishesEarlySkipFromLateSkipAndInterruptions() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            catalog.catalogDao().upsertRecording(RecordingEntity("rec:skip", "Skip", "Artist", 200_000L))
            var index = 0
            val playback = RoomListeningEventStore(
                listening.listeningEventDao(),
                clock = ListeningClock { 1_000_000L },
                idGenerator = ListeningIdGenerator { "event-" + (++index) },
            )
            playback.recordPlaybackStarted(PlaybackStart(recordingId = "rec:skip"))
            playback.recordPlaybackEnded(PlaybackEnd(
                recordingId = "rec:skip", listenedDurationMs = 10_000L,
                recordingDurationMs = 200_000L, endCause = PlaybackEndCause.NEXT,
            ))
            playback.recordPlaybackStarted(PlaybackStart(recordingId = "rec:skip"))
            playback.recordPlaybackEnded(PlaybackEnd(
                recordingId = "rec:skip", listenedDurationMs = 100_000L,
                recordingDurationMs = 200_000L, endCause = PlaybackEndCause.MANUAL_CHANGE,
            ))
            playback.recordPlaybackStarted(PlaybackStart(recordingId = "rec:skip"))
            playback.recordPlaybackEnded(PlaybackEnd(
                recordingId = "rec:skip", listenedDurationMs = 10_000L,
                recordingDurationMs = 200_000L, endCause = PlaybackEndCause.ERROR,
            ))
            val row = RoomSmartPlaylistBatchEvidenceReader(
                catalog.catalogDao(), listening.listeningEventDao(), listening.recordingRatingDao(),
            ).read(listOf("rec:skip")).single()
            assertEquals(1L, row.earlySkips)
            assertEquals(1L, row.lateSkips)
            assertEquals(0L, row.audibleCompletions)
        } finally {
            catalog.close()
            listening.close()
        }
    }

    @Test fun releaseTrackJoinIsBatchedAndKeepsMultipleReleasesWithoutTitleGuessing() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:linked", "A", "Artist", 180_000L))
            dao.upsertRecording(RecordingEntity("rec:orphan", "B", "Artist", 120_000L))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:get-up", "Get Up", "NewJeans", null,
            ))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:super-shy", "Super Shy", "NewJeans", null,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:get-up", "rec:linked", 1,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:super-shy", "rec:linked", 2,
            ))
            val evidence = RoomSmartPlaylistBatchEvidenceReader(
                dao, listening.listeningEventDao(), listening.recordingRatingDao(),
            ).read(listOf("rec:orphan", "rec:linked"))
            assertEquals(2, evidence.size)
            assertEquals(listOf("Get Up", "Super Shy"),
                evidence.single { it.recordingId == "rec:linked" }.releaseTitles)
            assertTrue(evidence.single { it.recordingId == "rec:orphan" }.releaseTitles.isEmpty())
        } finally {
            catalog.close()
            listening.close()
        }
    }

    @Test fun titleOnlyV2SkipsRemoteRatingSnapshotButRatingSortAndRuleStillReadIt() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            catalog.catalogDao().upsertRecording(RecordingEntity("rec:shy", "Super Shy", "NewJeans", 120_000L))
            var remoteReads = 0
            val preview = CanonicalRuleBasedSmartPlaylistPreview(
                availability = CanonicalLibraryAvailabilityReader { listOf("rec:shy") },
                catalog = catalog.catalogDao(),
                ratings = RoomRecordingRatingStore(listening.recordingRatingDao()),
                listening = RoomListeningEventStore(listening.listeningEventDao()),
                batchReader = RoomSmartPlaylistBatchEvidenceReader(
                    catalog.catalogDao(), listening.listeningEventDao(),
                    listening.recordingRatingDao(),
                ),
                remoteRatings = RemoteSmartPlaylistRatingReader {
                    remoteReads++
                    RemoteSmartPlaylistRatingSnapshot(complete = true, evidence = emptyList())
                },
            )
            val title = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.CONTAINS,
                textValue = "shy",
            )
            assertEquals(1, preview.preview(SmartPlaylistV2Query(
                title, sort = SmartPlaylistV2Sort.TITLE_ASC,
            )).size)
            assertEquals(0, remoteReads)
            preview.preview(SmartPlaylistV2Query(title, sort = SmartPlaylistV2Sort.RATING_DESC))
            assertEquals(1, remoteReads)
            val combined = SmartPlaylistV2Query(
                SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                    title,
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.EXPLICIT_RATING,
                        SmartPlaylistRuleOperator.IS_MISSING,
                    ),
                )),
                sort = SmartPlaylistV2Sort.TITLE_ASC,
            )
            preview.preview(combined)
            assertEquals(2, remoteReads)
        } finally {
            catalog.close()
            listening.close()
        }
    }

    @Test fun sourceRuleIntegratesWithCanonicalBatchAndNeverFallsBackToNativeIdentity() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            catalog.catalogDao().upsertRecording(RecordingEntity(
                "rec:local", "Song", "Artist", 150_000L,
            ))
            catalog.catalogDao().upsertRecording(RecordingEntity(
                "rec:remote", "Song", "Artist", 150_000L,
            ))
            val available = object : CanonicalLibraryAvailabilityReader {
                override suspend fun availableRecordingIds() = listOf("rec:local", "rec:remote")
                override suspend fun availableRecordingSources() = listOf(
                    CanonicalAvailableRecordingSources(
                        "rec:local", setOf(CanonicalLibrarySourceKind.LOCAL),
                    ),
                    CanonicalAvailableRecordingSources(
                        "rec:remote", setOf(CanonicalLibrarySourceKind.OPENSUBSONIC),
                    ),
                )
            }
            val preview = CanonicalRuleBasedSmartPlaylistPreview(
                availability = available,
                catalog = catalog.catalogDao(),
                ratings = RoomRecordingRatingStore(listening.recordingRatingDao()),
                listening = RoomListeningEventStore(listening.listeningEventDao()),
                batchReader = RoomSmartPlaylistBatchEvidenceReader(
                    catalog.catalogDao(), listening.listeningEventDao(), listening.recordingRatingDao(),
                ),
            )
            val result = preview.preview(SmartPlaylistV2Query(
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.SOURCE_KIND,
                    SmartPlaylistRuleOperator.EQUAL,
                    textValue = "opensubsonic",
                ), sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
            ))
            assertEquals(listOf("rec:remote"), result.map { it.recordingId })
        } finally {
            catalog.close()
            listening.close()
        }
    }

    @Test fun musicBrainzReleaseYearAcceptsPartialDatesButRejectsInvalidCalendarDates() {
        assertEquals(2026, parseTrustedMusicBrainzReleaseYear("2026"))
        assertEquals(2026, parseTrustedMusicBrainzReleaseYear("2026-10"))
        assertEquals(2024, parseTrustedMusicBrainzReleaseYear("2024-02-29"))
        assertEquals(null, parseTrustedMusicBrainzReleaseYear("2025-02-29"))
        assertEquals(null, parseTrustedMusicBrainzReleaseYear("2026-13"))
        assertEquals(null, parseTrustedMusicBrainzReleaseYear("2026-06-31"))
        assertEquals(null, parseTrustedMusicBrainzReleaseYear("2026?"))
        assertEquals(null, parseTrustedMusicBrainzReleaseYear(null))
    }

    @Test fun releaseYearJoinsOnlyEnrichedCanonicalReleaseTrackLinks() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:linked", "Song", "Artist", 200_000L))
            dao.upsertRecording(RecordingEntity("rec:orphan", "Other", "Artist", 150_000L))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:2026", "Real EP", "Artist", null,
            ))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:undated", "Other Edition", "Artist", null,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:2026", "rec:linked", 1,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:undated", "rec:linked", 1,
            ))
            dao.upsertMusicBrainzReleaseEnrichment(
                com.music.bitchord.data.catalog.MusicBrainzReleaseEnrichmentEntity(
                    releaseId = "rel:2026", mbid = "mb:valid", title = "Real EP",
                    artistDisplay = "Artist", releaseDate = "2026-10-20",
                    country = null, status = null, fetchedAtEpochMs = 1L,
                ),
            )
            val reader = RoomSmartPlaylistBatchEvidenceReader(
                dao, listening.listeningEventDao(), listening.recordingRatingDao(),
            )
            val rows = reader.read(listOf("rec:linked", "rec:orphan")).associateBy { it.recordingId }
            assertEquals(listOf(2026), rows.getValue("rec:linked").releaseYears)
            assertTrue(rows.getValue("rec:linked").releaseYearEvidenceUnknown)
            assertTrue(rows.getValue("rec:orphan").releaseYears.isEmpty())
            assertTrue(rows.getValue("rec:orphan").releaseYearEvidenceUnknown)
        } finally {
            catalog.close()
            listening.close()
        }
    }

    @Test fun albumArtistCreditsAreAttachedOnlyThroughCanonicalLinkedReleases() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:linked", "Song", "Singer", 180_000L))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:one", "Album", "Singer", null,
            ))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:no-credits", "Unknown credits", "Singer", null,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:one", "rec:linked", 1,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:no-credits", "rec:linked", 1,
            ))
            dao.upsertArtist(com.music.bitchord.data.catalog.ArtistEntity(
                "artist:canonical", "Known Album Artist",
            ))
            dao.upsertReleaseArtistCredits(listOf(
                com.music.bitchord.data.catalog.ReleaseArtistCreditEntity(
                    "rel:one", 0, "artist:canonical",
                ),
            ))
            val reader = RoomSmartPlaylistBatchEvidenceReader(
                dao, listening.listeningEventDao(), listening.recordingRatingDao(),
            )
            val row = reader.read(listOf("rec:linked")).single()
            assertEquals(listOf("Known Album Artist"), row.albumArtistNames)
            assertTrue(row.albumArtistEvidenceUnknown) // Second release lacks credits
        } finally {
            catalog.close()
            listening.close()
        }
    }


    @Test fun trackPositionsComeOnlyFromVerifiedCanonicalReleaseMemberships() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:linked", "Song", "Singer", 180_000L))
            dao.upsertRecording(RecordingEntity("rec:orphan", "Unknown", "Singer", 180_000L))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:first", "Edition A", "Singer", null,
            ))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:unknown", "Edition B", "Singer", null,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:first", "rec:linked", 2,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:unknown", "rec:linked", null,
            ))
            val rows = RoomSmartPlaylistBatchEvidenceReader(
                dao, listening.listeningEventDao(), listening.recordingRatingDao(),
            ).read(listOf("rec:linked", "rec:orphan")).associateBy { it.recordingId }
            assertEquals(listOf(2), rows.getValue("rec:linked").trackNumbers)
            assertTrue(rows.getValue("rec:linked").trackNumberEvidenceUnknown)
            assertTrue(rows.getValue("rec:orphan").trackNumbers.isEmpty())
            assertTrue(rows.getValue("rec:orphan").trackNumberEvidenceUnknown)
        } finally {
            catalog.close()
            listening.close()
        }
    }


    @Test fun trackArtistCreditsReadInBatchWithoutGuessingTheDisplayArtist() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:credited", "Duet", "Lead & Guest", 220_000L))
            dao.upsertRecording(RecordingEntity("rec:display-only", "Solo", "Lead", 140_000L))
            dao.upsertArtist(com.music.bitchord.data.catalog.ArtistEntity("artist:lead", "Lead"))
            dao.upsertArtist(com.music.bitchord.data.catalog.ArtistEntity("artist:guest", "Guest"))
            dao.upsertRecordingArtistCredits(listOf(
                com.music.bitchord.data.catalog.RecordingArtistCreditEntity(
                    "rec:credited", 0, "artist:lead",
                ),
                com.music.bitchord.data.catalog.RecordingArtistCreditEntity(
                    "rec:credited", 1, "artist:guest",
                ),
            ))
            val rows = RoomSmartPlaylistBatchEvidenceReader(
                dao, listening.listeningEventDao(), listening.recordingRatingDao(),
            ).read(listOf("rec:credited", "rec:display-only")).associateBy { it.recordingId }
            assertEquals(listOf("Guest", "Lead"), rows.getValue("rec:credited").trackArtistNames)
            assertTrue(!rows.getValue("rec:credited").trackArtistEvidenceUnknown)
            assertTrue(rows.getValue("rec:display-only").trackArtistNames.isEmpty())
            assertTrue(rows.getValue("rec:display-only").trackArtistEvidenceUnknown)
        } finally {
            catalog.close()
            listening.close()
        }
    }


    @Test fun roomBatchCanSkipUnrequestedRelationProjections() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:one", "Song", "Singer", 180_000L))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:one", "Edition", "Singer", null,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:one", "rec:one", 2,
            ))
            val reader = RoomSmartPlaylistBatchEvidenceReader(
                dao, listening.listeningEventDao(), listening.recordingRatingDao(),
            )
            val sparse = reader.read(listOf("rec:one"), emptySet()).single()
            assertTrue(sparse.releaseTitles.isEmpty())
            assertTrue(sparse.trackNumbers.isEmpty())
            assertTrue(sparse.trackArtistNames.isEmpty())
            assertEquals(180_000L, sparse.durationMs)
            val rich = reader.read(listOf("rec:one"), setOf(
                SmartPlaylistRuleField.RELEASE_TITLE, SmartPlaylistRuleField.TRACK_NUMBER,
            )).single()
            assertEquals(listOf("Edition"), rich.releaseTitles)
            assertEquals(listOf(2), rich.trackNumbers)
            assertTrue(rich.trackArtistNames.isEmpty())
        } finally {
            catalog.close()
            listening.close()
        }
    }



    @Test fun localRatingDateRequiresFirstPartyRoomReadAndCannotBeInvented() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:rated", "Song", "Artist", 100_000L))
            dao.upsertRecording(RecordingEntity("rec:unrated", "Other", "Artist", 100_000L))
            listening.recordingRatingDao().upsertCurrent(
                RecordingRatingCurrentEntity("rec:rated", 4.0, 86_400_000L),
            )
            val reader = RoomSmartPlaylistBatchEvidenceReader(
                dao, listening.listeningEventDao(), listening.recordingRatingDao(),
            )
            val rows = reader.read(listOf("rec:rated", "rec:unrated"), setOf(
                SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING,
            )).associateBy { it.recordingId }
            assertEquals(86_400_000L, rows.getValue("rec:rated").localRatingUpdatedAtEpochMs)
            assertEquals(null, rows.getValue("rec:unrated").localRatingUpdatedAtEpochMs)
            assertTrue(rows.getValue("rec:unrated").localRatingDateEvidenceKnown)
            val sparse = reader.read(listOf("rec:rated"), emptySet()).single()
            assertTrue(!sparse.localRatingDateEvidenceKnown)
            assertEquals(null, sparse.localRatingUpdatedAtEpochMs)
        } finally {
            catalog.close()
            listening.close()
        }
    }


    @Test fun releaseArtworkReferenceRequiresExplicitCanonicalReleaseLink() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:linked", "Song", "Singer", 120_000L))
            dao.upsertRecording(RecordingEntity("rec:orphan", "Other", "Singer", 120_000L))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:art", "With art", "Singer", "https://example.org/front.jpg",
            ))
            dao.upsertRelease(com.music.bitchord.data.catalog.ReleaseEntity(
                "rel:unknown", "Without art", "Singer", null,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:art", "rec:linked", 1,
            ))
            dao.upsertReleaseTrack(com.music.bitchord.data.catalog.ReleaseTrackEntity(
                "rel:unknown", "rec:linked", 1,
            ))
            val reader = RoomSmartPlaylistBatchEvidenceReader(
                dao, listening.listeningEventDao(), listening.recordingRatingDao(),
            )
            val rows = reader.read(listOf("rec:linked", "rec:orphan"), setOf(
                SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE,
            )).associateBy { it.recordingId }
            assertTrue(rows.getValue("rec:linked").hasReleaseArtworkReference)
            assertTrue(!rows.getValue("rec:orphan").hasReleaseArtworkReference)
            val sparse = reader.read(listOf("rec:linked"), emptySet()).single()
            assertTrue(!sparse.hasReleaseArtworkReference)
        } finally {
            catalog.close()
            listening.close()
        }
    }



    @Test fun dateOnlySortFetchesActualRatingTimestampsForEachCandidate() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:old", "Old", "Artist", 120_000L))
            dao.upsertRecording(RecordingEntity("rec:new", "New", "Artist", 120_000L))
            dao.upsertRecording(RecordingEntity("rec:none", "None", "Artist", 120_000L))
            listening.recordingRatingDao().upsertCurrent(
                RecordingRatingCurrentEntity("rec:old", 4.0, 1_000L),
            )
            listening.recordingRatingDao().upsertCurrent(
                RecordingRatingCurrentEntity("rec:new", 2.0, 2_000L),
            )
            val preview = CanonicalRuleBasedSmartPlaylistPreview(
                availability = CanonicalLibraryAvailabilityReader {
                    listOf("rec:old", "rec:new", "rec:none")
                },
                catalog = dao,
                ratings = RoomRecordingRatingStore(listening.recordingRatingDao()),
                listening = RoomListeningEventStore(listening.listeningEventDao()),
                batchReader = RoomSmartPlaylistBatchEvidenceReader(
                    dao, listening.listeningEventDao(), listening.recordingRatingDao(),
                ),
            )
            val rule = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DURATION_MS, SmartPlaylistRuleOperator.IS_PRESENT,
            )
            val sorted = preview.preview(SmartPlaylistV2Query(
                rule, sort = SmartPlaylistV2Sort.LOCAL_RATING_NEWEST,
            ))
            assertEquals(listOf("rec:new", "rec:old", "rec:none"),
                sorted.map { it.recordingId })
        } finally {
            catalog.close()
            listening.close()
        }
    }



    @Test fun localAddedDatesReadOnlyMediaStoreAssetsAndPreserveUnknownValues() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = catalog.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:one", "Song", "Artist", 120_000L))
            dao.upsertRecording(RecordingEntity("rec:two", "Other", "Artist", 120_000L))
            val provider = com.music.bitchord.data.catalog.CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
            dao.upsertMediaAsset(com.music.bitchord.data.catalog.MediaAssetEntity(
                "asset:one", "rec:one", provider, "local:one",
                "content://music/one", null, 86_400L, null, null,
            ))
            dao.upsertMediaAsset(com.music.bitchord.data.catalog.MediaAssetEntity(
                "asset:unknown", "rec:one", provider, "local:unknown",
                "content://music/unknown", null, null, null, null,
            ))
            dao.upsertMediaAsset(com.music.bitchord.data.catalog.MediaAssetEntity(
                "asset:remote", "rec:one", "opensubsonic:test", "remote:one",
                "https://remote.example/one", null, 864_000L, null, null,
            ))
            val reader = RoomSmartPlaylistBatchEvidenceReader(
                dao, listening.listeningEventDao(), listening.recordingRatingDao(),
            )
            val values = reader.read(listOf("rec:one", "rec:two"), setOf(
                SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
            )).associateBy { it.recordingId }
            assertEquals(listOf(86_400_000L), values.getValue("rec:one").localAddedEpochMs)
            assertTrue(values.getValue("rec:one").localAddedDateEvidenceUnknown)
            assertTrue(values.getValue("rec:two").localAddedDateEvidenceUnknown)
            assertTrue(values.getValue("rec:two").localAddedEpochMs.isEmpty())
            assertTrue(reader.read(listOf("rec:one"), emptySet())
                .single().localAddedEpochMs.isEmpty())
        } finally {
            catalog.close()
            listening.close()
        }
    }


    @Test fun savedReferencesReevaluateUsingOneCanonicalSnapshotAndFailClosedOnDeletion() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        val definitions = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = SmartPlaylistV2DefinitionRepository(definitions.v2DefinitionDao())
            val ids = listOf("rec:a", "rec:b", "rec:c")
            var availabilityReads = 0
            var evidenceReads = 0
            val availability = CanonicalLibraryAvailabilityReader {
                availabilityReads++
                ids
            }
            val batch = SmartPlaylistBatchEvidenceReader { incoming ->
                evidenceReads++
                incoming.map { id ->
                    val duration = when (id) {
                        "rec:a" -> 100_000L
                        "rec:b" -> 200_000L
                        else -> 300_000L
                    }
                    RuleBasedRecordingCandidate(id, null, 0L, null, duration, true)
                }
            }
            val preview = CanonicalRuleBasedSmartPlaylistPreview(
                availability = availability,
                catalog = catalog.catalogDao(),
                ratings = RoomRecordingRatingStore(listening.recordingRatingDao()),
                listening = RoomListeningEventStore(listening.listeningEventDao()),
                batchReader = batch,
                v2Planner = SmartPlaylistV2RuleEngine(nowEpochMs = { 900_000L }),
                savedReferences = { repo.list() },
            )
            fun base(above: Double) = SavedSmartPlaylistV2Definition(
                "base", "Base",
                SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.DURATION_MS,
                    SmartPlaylistRuleOperator.GREATER_THAN, above,
                ), sort = SmartPlaylistV2Sort.RECORDING_ID_ASC, limit = 1),
            )
            val dependent = SavedSmartPlaylistV2Definition(
                "dependent", "Dependent", SmartPlaylistV2Query(
                    SmartPlaylistRule.Reference("base"),
                ),
            )
            repo.save(base(150_000.0))
            repo.save(dependent)
            val root = SmartPlaylistV2Query(SmartPlaylistRule.Reference("dependent"))
            assertEquals(listOf("rec:b"), preview.preview(root).map { it.recordingId })
            assertEquals(1, availabilityReads)
            assertEquals(1, evidenceReads)
            repo.save(base(250_000.0))
            assertEquals(listOf("rec:c"), preview.preview(root).map { it.recordingId })
            assertEquals(2, availabilityReads)
            assertEquals(2, evidenceReads)
            // Simulate a previously damaged/externally replaced DB row.
            definitions.v2DefinitionDao().remove("base")
            assertTrue(runCatching { preview.preview(root) }.exceptionOrNull()
                is IllegalArgumentException)
            // A missing reference is NOT an empty, successfully evaluated playlist.
            assertEquals(2, availabilityReads)
        } finally {
            definitions.close()
            catalog.close()
            listening.close()
        }
    }
}
