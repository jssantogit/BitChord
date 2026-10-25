package com.music.bitchord.data.catalog

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A ReleaseGroup is a conceptual album/EP/single, not a specific edition.
 * Its opaque local id may be claimed by a MusicBrainz release-group MBID.
 */
@Entity(tableName = "catalog_release_group")
internal data class CatalogReleaseGroupEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistDisplay: String?,
    val primaryType: String?,
    val firstReleaseDate: String?,
)

/** Multiple territorial/format releases can belong to one ReleaseGroup. */
@Entity(
    tableName = "catalog_release_group_edition",
    primaryKeys = ["releaseGroupId", "releaseId"],
    foreignKeys = [
        ForeignKey(entity = CatalogReleaseGroupEntity::class, parentColumns = ["id"],
            childColumns = ["releaseGroupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReleaseEntity::class, parentColumns = ["id"],
            childColumns = ["releaseId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("releaseId")],
)
internal data class CatalogReleaseGroupEditionEntity(
    val releaseGroupId: String,
    val releaseId: String,
)

/**
 * MusicBrainz Track MBID belongs to a specific release+medium+position and
 * MUST NOT be conflated with a Recording MBID shared across editions.
 * This sidecar preserves the legacy ReleaseTrack composite key unchanged.
 */
@Entity(
    tableName = "catalog_musicbrainz_track",
    foreignKeys = [
        ForeignKey(entity = ReleaseEntity::class, parentColumns = ["id"],
            childColumns = ["releaseId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"],
            childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("releaseId"), Index("recordingId")],
)
internal data class CatalogMusicBrainzTrackEntity(
    @PrimaryKey val trackMbid: String,
    val releaseId: String,
    val recordingId: String,
    val mediumNumber: Int,
    val trackNumber: Int,
    val title: String,
    val durationMs: Long?,
)
