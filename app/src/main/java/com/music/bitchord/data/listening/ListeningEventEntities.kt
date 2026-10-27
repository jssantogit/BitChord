package com.music.bitchord.data.listening

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "listening_session")
internal data class ListeningSessionEntity(
    @PrimaryKey val id: String,
    val startedAtEpochMs: Long,
    val lastActivityAtEpochMs: Long,
    val endedAtEpochMs: Long?,
)

@Entity(
    tableName = "listening_event",
    foreignKeys = [
        ForeignKey(
            entity = ListeningSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["recordingId", "occurredAtEpochMs"]),
    ],
)
internal data class ListeningEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val recordingId: String,
    val mediaAssetId: String?,
    val assetProvider: String?,
    val occurredAtEpochMs: Long,
    val type: String,
    val listenedDurationMs: Long?,
    val recordingDurationMs: Long?,
    val endCause: String?,
    val selectionIntent: String?,
    val selectionOrigin: String?,
    val recommendationOrigin: String?,
)

internal data class RecordingBehaviorSummaryRow(
    val starts: Long,
    val completions: Long,
    val earlySkips: Long,
    val lateSkips: Long,
    val interruptions: Long,
    val totalListenedMs: Long,
    val lastEventAtEpochMs: Long?,
)


/** Aggregates only terminal audible completion facts; never counts PLAYBACK_STARTED. */
internal data class SmartPlaylistBehaviorEvidenceRow(
    val recordingId: String,
    val completions: Long,
    val lastAudibleAtEpochMs: Long?,
    val earlySkips: Long,
    val lateSkips: Long,
)
