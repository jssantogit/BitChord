package com.music.bitchord.data.listening

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface ListeningEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ListeningSessionEntity)

    @Query(
        """
        UPDATE listening_session
        SET lastActivityAtEpochMs = :lastActivityAtEpochMs
        WHERE id = :sessionId AND endedAtEpochMs IS NULL
        """,
    )
    suspend fun touchSession(sessionId: String, lastActivityAtEpochMs: Long): Int

    @Query(
        """
        UPDATE listening_session
        SET lastActivityAtEpochMs = :endedAtEpochMs,
            endedAtEpochMs = :endedAtEpochMs
        WHERE id = :sessionId AND endedAtEpochMs IS NULL
        """,
    )
    suspend fun endSession(sessionId: String, endedAtEpochMs: Long): Int

    @Query(
        """
        SELECT
            recordingId AS recordingId,
            SUM(CASE WHEN type = 'PLAYBACK_COMPLETED' THEN 1 ELSE 0 END) AS completions,
            SUM(CASE WHEN type = 'PLAYBACK_SKIPPED_EARLY' THEN 1 ELSE 0 END) AS earlySkips,
            SUM(CASE WHEN type = 'PLAYBACK_SKIPPED_LATE' THEN 1 ELSE 0 END) AS lateSkips,
            MAX(CASE WHEN type = 'PLAYBACK_COMPLETED' AND listenedDurationMs > 0
                     THEN occurredAtEpochMs ELSE NULL END) AS lastAudibleAtEpochMs
        FROM listening_event
        WHERE recordingId IN (:recordingIds)
        GROUP BY recordingId
        """,
    )
    suspend fun smartPlaylistBehaviorForIds(
        recordingIds: List<String>,
    ): List<SmartPlaylistBehaviorEvidenceRow>

    @Query("SELECT * FROM listening_session WHERE id = :sessionId")
    suspend fun session(sessionId: String): ListeningSessionEntity?

    @Query(
        """
        SELECT * FROM listening_session
        WHERE endedAtEpochMs IS NULL
        ORDER BY startedAtEpochMs DESC
        LIMIT 1
        """,
    )
    suspend fun activeSession(): ListeningSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: ListeningEventEntity)

    @Query(
        """
        SELECT * FROM listening_event
        WHERE sessionId = :sessionId
        ORDER BY occurredAtEpochMs ASC, id ASC
        """,
    )
    suspend fun eventsForSession(sessionId: String): List<ListeningEventEntity>

    @Query(
        """
        SELECT * FROM listening_event
        WHERE recordingId = :recordingId
        ORDER BY occurredAtEpochMs DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun recentEventsForRecording(
        recordingId: String,
        limit: Int,
    ): List<ListeningEventEntity>

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN type = 'PLAYBACK_STARTED' THEN 1 ELSE 0 END), 0) AS starts,
            COALESCE(SUM(CASE WHEN type = 'PLAYBACK_COMPLETED' THEN 1 ELSE 0 END), 0) AS completions,
            COALESCE(SUM(CASE WHEN type = 'PLAYBACK_SKIPPED_EARLY' THEN 1 ELSE 0 END), 0) AS earlySkips,
            COALESCE(SUM(CASE WHEN type = 'PLAYBACK_SKIPPED_LATE' THEN 1 ELSE 0 END), 0) AS lateSkips,
            COALESCE(SUM(CASE WHEN type = 'PLAYBACK_INTERRUPTED' THEN 1 ELSE 0 END), 0) AS interruptions,
            COALESCE(SUM(CASE WHEN listenedDurationMs IS NOT NULL THEN listenedDurationMs ELSE 0 END), 0) AS totalListenedMs,
            MAX(occurredAtEpochMs) AS lastEventAtEpochMs
        FROM listening_event
        WHERE recordingId = :recordingId
        """,
    )
    suspend fun behaviorSummary(recordingId: String): RecordingBehaviorSummaryRow
}
