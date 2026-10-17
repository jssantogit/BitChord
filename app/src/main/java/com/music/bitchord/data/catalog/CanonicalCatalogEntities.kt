package com.music.bitchord.data.catalog

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "catalog_recording")
data class RecordingEntity(
    @androidx.room.PrimaryKey
    val id: String,
    val title: String,
    val artistDisplay: String,
    val durationMs: Long?,
)

@Entity(tableName = "catalog_release")
data class ReleaseEntity(
    @androidx.room.PrimaryKey
    val id: String,
    val title: String,
    val artistDisplay: String?,
    val artworkUri: String?,
)

@Entity(
    tableName = "catalog_release_track",
    primaryKeys = ["releaseId", "recordingId"],
    foreignKeys = [
        ForeignKey(
            entity = ReleaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["releaseId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class ReleaseTrackEntity(
    val releaseId: String,
    val recordingId: String,
    val trackNumber: Int?,
)

@Entity(
    tableName = "catalog_media_asset",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("recordingId"),
        Index(value = ["provider", "sourceKey"], unique = true),
    ],
)
data class MediaAssetEntity(
    @androidx.room.PrimaryKey
    val id: String,
    val recordingId: String,
    val provider: String,
    val sourceKey: String,
    val uri: String,
    val path: String?,
    val dateAddedSeconds: Long?,
    val dateModifiedSeconds: Long?,
    val artworkUri: String?,
)

/**
 * Provider/native identifiers are aliases of our entities, never their primary
 * keys. `entityId` is intentionally polymorphic in v1, so it cannot carry a
 * Room foreign key until identity tables are split by canonical entity type.
 */
@Entity(
    tableName = "catalog_external_identity",
    primaryKeys = ["provider", "entityType", "externalId"],
    indices = [Index("entityId")],
)
data class ExternalIdentityEntity(
    val provider: String,
    val entityType: String,
    val externalId: String,
    val entityId: String,
)
