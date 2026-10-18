package com.music.bitchord.data.listening

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Current first-party explicit preference for one canonical Recording. */
internal data class RecordingRating(
    val recordingId: String,
    val value: Double,
    val ratedAtEpochMs: Long,
)

internal enum class RecordingRatingChangeType {
    SET,
    CLEAR,
}

internal data class RecordingRatingChange(
    val sequence: Long,
    val recordingId: String,
    val previousValue: Double?,
    val newValue: Double?,
    val changedAtEpochMs: Long,
    val type: RecordingRatingChangeType,
)

internal enum class RecordingRatingDirection {
    INITIAL_SET,
    INCREASED,
    DECREASED,
    UNCHANGED,
    CLEARED,
}

/**
 * Small recommendation-facing read model. Timestamps intentionally expose recency
 * without calculating or owning a final recommendation score.
 */
internal data class ExplicitRecordingRatingSignal(
    val recordingId: String,
    val currentRating: Double?,
    val currentRatedAtEpochMs: Long?,
    val lastChangedAtEpochMs: Long?,
    val lastChangeDirection: RecordingRatingDirection?,
    val hasExplicitFeedback: Boolean,
)

internal interface ExplicitRecordingRatingReader {
    suspend fun currentRating(recordingId: String): RecordingRating?
    fun observeRating(recordingId: String): Flow<RecordingRating?>
    suspend fun ratedRecordings(limit: Int = Int.MAX_VALUE): List<RecordingRating>
    suspend fun explicitRatingSignal(recordingId: String): ExplicitRecordingRatingSignal
}

internal interface RecordingRatingStore : ExplicitRecordingRatingReader {
    suspend fun setRecordingRating(recordingId: String, value: Double)
    suspend fun clearRecordingRating(recordingId: String)
    suspend fun ratingHistory(recordingId: String): List<RecordingRatingChange>
}

/**
 * First-party explicit preference store. The write/read API deliberately accepts
 * canonical recordingId only: provider/native identity and MediaAsset are not part
 * of this boundary.
 *
 * Numeric values are preserved as explicit preference semantics; this layer does
 * not reinterpret them as a generic linear star score or combine them with playback.
 */
internal class RoomRecordingRatingStore(
    private val dao: RecordingRatingDao,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : RecordingRatingStore {
    override suspend fun setRecordingRating(recordingId: String, value: Double) {
        validateRecordingId(recordingId)
        require(value.isFinite()) { "Recording rating must be finite" }
        dao.setRating(recordingId, value, nowEpochMs())
    }

    override suspend fun clearRecordingRating(recordingId: String) {
        validateRecordingId(recordingId)
        dao.clearRating(recordingId, nowEpochMs())
    }

    override suspend fun currentRating(recordingId: String): RecordingRating? {
        validateRecordingId(recordingId)
        return dao.current(recordingId)?.toDomain()
    }

    override fun observeRating(recordingId: String): Flow<RecordingRating?> {
        validateRecordingId(recordingId)
        return dao.observeCurrent(recordingId).map { it?.toDomain() }
    }

    override suspend fun ratingHistory(recordingId: String): List<RecordingRatingChange> {
        validateRecordingId(recordingId)
        return dao.history(recordingId).map { it.toDomain() }
    }

    override suspend fun ratedRecordings(limit: Int): List<RecordingRating> {
        require(limit > 0) { "Rating query limit must be positive" }
        return dao.ratedRecordings(limit).map { it.toDomain() }
    }

    override suspend fun explicitRatingSignal(recordingId: String): ExplicitRecordingRatingSignal {
        validateRecordingId(recordingId)
        val rows = dao.snapshot(recordingId)
        val current = rows.current
        val latest = rows.latestChange
        return ExplicitRecordingRatingSignal(
            recordingId = recordingId,
            currentRating = current?.ratingValue,
            currentRatedAtEpochMs = current?.ratedAtEpochMs,
            lastChangedAtEpochMs = latest?.changedAtEpochMs,
            lastChangeDirection = latest?.direction(),
            hasExplicitFeedback = latest != null,
        )
    }

    private fun validateRecordingId(recordingId: String) {
        require(recordingId.isNotBlank()) { "Canonical recordingId must not be blank" }
    }

    private fun RecordingRatingCurrentEntity.toDomain() = RecordingRating(
        recordingId = recordingId,
        value = ratingValue,
        ratedAtEpochMs = ratedAtEpochMs,
    )

    private fun RecordingRatingHistoryEntity.toDomain() = RecordingRatingChange(
        sequence = sequence,
        recordingId = recordingId,
        previousValue = previousRatingValue,
        newValue = newRatingValue,
        changedAtEpochMs = changedAtEpochMs,
        type = when (operation) {
            RecordingRatingDao.OPERATION_SET -> RecordingRatingChangeType.SET
            RecordingRatingDao.OPERATION_CLEAR -> RecordingRatingChangeType.CLEAR
            else -> error("Unknown recording rating operation: $operation")
        },
    )

    private fun RecordingRatingHistoryEntity.direction(): RecordingRatingDirection = when {
        operation == RecordingRatingDao.OPERATION_CLEAR -> RecordingRatingDirection.CLEARED
        previousRatingValue == null -> RecordingRatingDirection.INITIAL_SET
        newRatingValue == null -> RecordingRatingDirection.CLEARED
        newRatingValue > previousRatingValue -> RecordingRatingDirection.INCREASED
        newRatingValue < previousRatingValue -> RecordingRatingDirection.DECREASED
        else -> RecordingRatingDirection.UNCHANGED
    }
}
