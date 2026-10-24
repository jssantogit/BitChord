package com.music.bitchord.data.listening

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

internal data class RecordingRatingSnapshotRows(
    val current: RecordingRatingCurrentEntity?,
    val latestChange: RecordingRatingHistoryEntity?,
)

@Dao
internal interface RecordingRatingDao {
    @Query("SELECT * FROM recording_rating_current WHERE recordingId = :recordingId")
    suspend fun current(recordingId: String): RecordingRatingCurrentEntity?

    @Query("SELECT * FROM recording_rating_current WHERE recordingId = :recordingId")
    fun observeCurrent(recordingId: String): Flow<RecordingRatingCurrentEntity?>

    @Query(
        """
        SELECT * FROM recording_rating_current
        ORDER BY ratedAtEpochMs DESC, recordingId ASC
        LIMIT :limit
        """,
    )
    suspend fun ratedRecordings(limit: Int): List<RecordingRatingCurrentEntity>

    /** Live list for the first-party My Ratings Library, not a playback event feed. */
    @Query("SELECT * FROM recording_rating_current ORDER BY ratedAtEpochMs DESC, recordingId ASC LIMIT :limit")
    fun observeRatedRecordings(limit: Int): Flow<List<RecordingRatingCurrentEntity>>

    /** Read-only all-current batch for UI badges; Room invalidates on local edits. */
    @Query("SELECT * FROM recording_rating_current")
    fun observeAllCurrentRatings(): Flow<List<RecordingRatingCurrentEntity>>

    @Query(
        """
        SELECT * FROM recording_rating_history
        WHERE recordingId = :recordingId
        ORDER BY sequence ASC
        """,
    )
    suspend fun history(recordingId: String): List<RecordingRatingHistoryEntity>

    @Query(
        """
        SELECT * FROM recording_rating_history
        WHERE recordingId = :recordingId
        ORDER BY sequence DESC
        LIMIT 1
        """,
    )
    suspend fun latestChange(recordingId: String): RecordingRatingHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCurrent(entity: RecordingRatingCurrentEntity)

    @Query("DELETE FROM recording_rating_current WHERE recordingId = :recordingId")
    suspend fun deleteCurrent(recordingId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(entity: RecordingRatingHistoryEntity): Long

    @Transaction
    suspend fun setRating(recordingId: String, value: Double, changedAtEpochMs: Long) {
        val previous = current(recordingId)
        insertHistory(
            RecordingRatingHistoryEntity(
                recordingId = recordingId,
                previousRatingValue = previous?.ratingValue,
                newRatingValue = value,
                changedAtEpochMs = changedAtEpochMs,
                operation = OPERATION_SET,
            ),
        )
        upsertCurrent(
            RecordingRatingCurrentEntity(
                recordingId = recordingId,
                ratingValue = value,
                ratedAtEpochMs = changedAtEpochMs,
            ),
        )
    }

    @Transaction
    suspend fun clearRating(recordingId: String, changedAtEpochMs: Long) {
        val previous = current(recordingId) ?: return
        insertHistory(
            RecordingRatingHistoryEntity(
                recordingId = recordingId,
                previousRatingValue = previous.ratingValue,
                newRatingValue = null,
                changedAtEpochMs = changedAtEpochMs,
                operation = OPERATION_CLEAR,
            ),
        )
        deleteCurrent(recordingId)
    }

    @Transaction
    suspend fun snapshot(recordingId: String): RecordingRatingSnapshotRows =
        RecordingRatingSnapshotRows(
            current = current(recordingId),
            latestChange = latestChange(recordingId),
        )

    companion object {
        const val OPERATION_SET = "SET"
        const val OPERATION_CLEAR = "CLEAR"
    }
}
