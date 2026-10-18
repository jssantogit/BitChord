package com.music.bitchord.data.listening

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small playback-facing seam. Playback emits facts only; Listening &
 * Intelligence owns session identity and analytical boundaries.
 */
internal interface ListeningEventIngestor {
    suspend fun recordPlaybackStarted(start: PlaybackStart): ListeningEvent

    suspend fun recordPlaybackActivity(activity: PlaybackActivity)

    suspend fun recordPlaybackEnded(end: PlaybackEnd): ListeningEvent
}

internal interface ListeningBehaviorReader {
    suspend fun session(sessionId: String): ListeningSession?

    suspend fun eventsForSession(sessionId: String): List<ListeningEvent>

    suspend fun recentEventsForRecording(
        recordingId: String,
        limit: Int = DEFAULT_RECENT_EVENT_LIMIT,
    ): List<ListeningEvent>

    suspend fun behaviorSummary(recordingId: String): RecordingBehaviorSummary

    private companion object {
        const val DEFAULT_RECENT_EVENT_LIMIT = 50
    }
}

internal interface ListeningEventStore : ListeningEventIngestor, ListeningBehaviorReader

internal class RoomListeningEventStore(
    private val dao: ListeningEventDao,
    private val classifier: ListeningOutcomeClassifier = ListeningOutcomeClassifier(),
    private val clock: ListeningClock = ListeningClock { System.currentTimeMillis() },
    private val idGenerator: ListeningIdGenerator = ListeningIdGenerator { UUID.randomUUID().toString() },
) : ListeningEventStore {
    private val ingestionMutex = Mutex()

    override suspend fun recordPlaybackStarted(start: PlaybackStart): ListeningEvent =
        ingestionMutex.withLock {
            val now = clock.nowEpochMs()
            val session = activeSessionFor(now).session
            val event = startEvent(session.id, start, now)
            dao.insertEvent(event.toEntity())
            check(dao.touchSession(session.id, now) == 1)
            event
        }

    override suspend fun recordPlaybackActivity(activity: PlaybackActivity) {
        ingestionMutex.withLock {
            val now = clock.nowEpochMs()
            val resolution = activeSessionFor(now)
            if (resolution.created) {
                // Playback was still active after the previous analytical
                // session timed out (for example a resume after a long pause).
                // Start the new analytical period without making Playback know
                // or compare session ids.
                val event = startEvent(
                    sessionId = resolution.session.id,
                    start = PlaybackStart(
                        recordingId = activity.recordingId,
                        mediaAssetId = activity.mediaAssetId,
                        assetProvider = activity.assetProvider,
                        selectionIntent = SelectionIntent.UNKNOWN,
                        selectionOrigin = SelectionOrigin.UNKNOWN,
                    ),
                    occurredAtEpochMs = now,
                )
                dao.insertEvent(event.toEntity())
            }
            check(dao.touchSession(resolution.session.id, now) == 1)
        }
    }

    override suspend fun recordPlaybackEnded(end: PlaybackEnd): ListeningEvent =
        ingestionMutex.withLock {
            val now = clock.nowEpochMs()
            val session = activeSessionFor(now).session
            val event = ListeningEvent(
                id = nextEventId(),
                sessionId = session.id,
                recordingId = end.recordingId,
                mediaAssetId = end.mediaAssetId,
                assetProvider = end.assetProvider,
                occurredAtEpochMs = now,
                type = classifier.classify(
                    listenedDurationMs = end.listenedDurationMs,
                    recordingDurationMs = end.recordingDurationMs,
                    endCause = end.endCause,
                ),
                listenedDurationMs = end.listenedDurationMs,
                recordingDurationMs = end.recordingDurationMs,
                endCause = end.endCause,
                selectionIntent = null,
                selectionOrigin = null,
                recommendationOrigin = null,
            )
            dao.insertEvent(event.toEntity())
            if (end.endCause == PlaybackEndCause.STOPPED) {
                check(dao.endSession(session.id, now) == 1) {
                    "Listening session was already closed while recording explicit stop: ${session.id}"
                }
            } else {
                check(dao.touchSession(session.id, now) == 1)
            }
            event
        }

    override suspend fun session(sessionId: String): ListeningSession? {
        require(sessionId.isNotBlank())
        return dao.session(sessionId)?.toDomain()
    }

    override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> {
        require(sessionId.isNotBlank())
        return dao.eventsForSession(sessionId).map(ListeningEventEntity::toDomain)
    }

    override suspend fun recentEventsForRecording(
        recordingId: String,
        limit: Int,
    ): List<ListeningEvent> {
        require(recordingId.isNotBlank())
        require(limit > 0)
        return dao.recentEventsForRecording(recordingId, limit).map(ListeningEventEntity::toDomain)
    }

    override suspend fun behaviorSummary(recordingId: String): RecordingBehaviorSummary {
        require(recordingId.isNotBlank())
        val row = dao.behaviorSummary(recordingId)
        return RecordingBehaviorSummary(
            recordingId = recordingId,
            starts = row.starts,
            completions = row.completions,
            earlySkips = row.earlySkips,
            lateSkips = row.lateSkips,
            interruptions = row.interruptions,
            totalListenedMs = row.totalListenedMs,
            lastEventAtEpochMs = row.lastEventAtEpochMs,
        )
    }

    private suspend fun activeSessionFor(nowEpochMs: Long): SessionResolution {
        val active = dao.activeSession()
        if (active != null) {
            val inactivityMs = nowEpochMs - active.lastActivityAtEpochMs
            if (inactivityMs in 0L..SESSION_INACTIVITY_TIMEOUT_MS) {
                return SessionResolution(active.toDomain(), created = false)
            }
            check(dao.endSession(active.id, active.lastActivityAtEpochMs) == 1) {
                "Listening session was already closed during timeout rollover: ${active.id}"
            }
        }

        val session = ListeningSession(
            id = idGenerator.nextId().also { require(it.isNotBlank()) },
            startedAtEpochMs = nowEpochMs,
            lastActivityAtEpochMs = nowEpochMs,
            endedAtEpochMs = null,
        )
        dao.insertSession(session.toEntity())
        return SessionResolution(session, created = true)
    }

    private fun startEvent(
        sessionId: String,
        start: PlaybackStart,
        occurredAtEpochMs: Long,
    ): ListeningEvent = ListeningEvent(
        id = nextEventId(),
        sessionId = sessionId,
        recordingId = start.recordingId,
        mediaAssetId = start.mediaAssetId,
        assetProvider = start.assetProvider,
        occurredAtEpochMs = occurredAtEpochMs,
        type = ListeningEventType.PLAYBACK_STARTED,
        listenedDurationMs = null,
        recordingDurationMs = null,
        endCause = null,
        selectionIntent = start.selectionIntent,
        selectionOrigin = start.selectionOrigin,
        recommendationOrigin = start.recommendationOrigin,
    )

    private fun nextEventId(): String = idGenerator.nextId().also { require(it.isNotBlank()) }

    private data class SessionResolution(
        val session: ListeningSession,
        val created: Boolean,
    )

    private companion object {
        const val SESSION_INACTIVITY_TIMEOUT_MS = 30L * 60L * 1000L
    }
}

internal object ListeningEventStores {
    fun persistent(context: Context): ListeningEventStore =
        RoomListeningEventStore(
            dao = ListeningEventDatabase.get(context).listeningEventDao(),
        )
}

private fun ListeningSession.toEntity(): ListeningSessionEntity =
    ListeningSessionEntity(
        id = id,
        startedAtEpochMs = startedAtEpochMs,
        lastActivityAtEpochMs = lastActivityAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
    )

private fun ListeningSessionEntity.toDomain(): ListeningSession =
    ListeningSession(
        id = id,
        startedAtEpochMs = startedAtEpochMs,
        lastActivityAtEpochMs = lastActivityAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
    )

private fun ListeningEvent.toEntity(): ListeningEventEntity =
    ListeningEventEntity(
        id = id,
        sessionId = sessionId,
        recordingId = recordingId,
        mediaAssetId = mediaAssetId,
        assetProvider = assetProvider,
        occurredAtEpochMs = occurredAtEpochMs,
        type = type.name,
        listenedDurationMs = listenedDurationMs,
        recordingDurationMs = recordingDurationMs,
        endCause = endCause?.name,
        selectionIntent = selectionIntent?.name,
        selectionOrigin = selectionOrigin?.name,
        recommendationOrigin = recommendationOrigin,
    )

private fun ListeningEventEntity.toDomain(): ListeningEvent =
    ListeningEvent(
        id = id,
        sessionId = sessionId,
        recordingId = recordingId,
        mediaAssetId = mediaAssetId,
        assetProvider = assetProvider,
        occurredAtEpochMs = occurredAtEpochMs,
        type = ListeningEventType.valueOf(type),
        listenedDurationMs = listenedDurationMs,
        recordingDurationMs = recordingDurationMs,
        endCause = endCause?.let(PlaybackEndCause::valueOf),
        selectionIntent = selectionIntent?.let(SelectionIntent::valueOf),
        selectionOrigin = selectionOrigin?.let(SelectionOrigin::valueOf),
        recommendationOrigin = recommendationOrigin,
    )
