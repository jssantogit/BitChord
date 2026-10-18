package com.music.bitchord.data.listening

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "recording_rating_current")
internal data class RecordingRatingCurrentEntity(
    @PrimaryKey
    val recordingId: String,
    val ratingValue: Double,
    val ratedAtEpochMs: Long,
)

@Entity(
    tableName = "recording_rating_history",
    indices = [Index("recordingId")],
)
internal data class RecordingRatingHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val sequence: Long = 0L,
    val recordingId: String,
    val previousRatingValue: Double?,
    val newRatingValue: Double?,
    val changedAtEpochMs: Long,
    val operation: String,
)
