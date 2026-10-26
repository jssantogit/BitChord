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
    /** Null means no secondary-type evidence; never infer tags from titles. */
    val secondaryTypes: String? = null,
)

/**
 * Positive artist → release-group evidence from a MusicBrainz artist-MBID browse.
 * Never infer this edge from a display name (homonymous artists are distinct).
 * An incomplete or failed browse cannot remove previously known associations.
 */
@Entity(
    tableName = "catalog_musicbrainz_artist_group",
    primaryKeys = ["artistId", "releaseGroupId"],
    foreignKeys = [
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["id"],
            childColumns = ["artistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CatalogReleaseGroupEntity::class, parentColumns = ["id"],
            childColumns = ["releaseGroupId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("releaseGroupId")],
)
internal data class CatalogMusicBrainzArtistGroupEntity(
    val artistId: String,
    val releaseGroupId: String,
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
    /** The credit as listed for this edition-specific Track. */
    val artistDisplay: String? = null,
    /** The shared Recording credit; older rows remain unknown after migration. */
    val recordingArtistDisplay: String? = null,
)


/**
 * Bounded TTL cache for positive covers and 404 misses. A null URI is a
 * confirmed absence, not an excuse to reuse artwork from another release.
 * This table has no audio or user-private data.
 */
@Entity(tableName = "catalog_cover_art_cache", primaryKeys = ["entityType", "mbid"])
internal data class MusicBrainzCoverArtCacheEntity(
    val entityType: String,
    val mbid: String,
    val artworkUri: String?,
    val fetchedAtEpochMs: Long,
)
    

/**
 * Provenance belongs to an edge, not just to the Release: two providers may
 * link different Recordings to the very same canonical Release.
 */
@Entity(
    tableName = "catalog_release_track_source",
    primaryKeys = ["releaseId", "recordingId", "provider"],
    foreignKeys = [ForeignKey(
        entity = ReleaseTrackEntity::class,
        parentColumns = ["releaseId", "recordingId"],
        childColumns = ["releaseId", "recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("provider")],
)
internal data class CatalogReleaseTrackSourceEntity(
    val releaseId: String,
    val recordingId: String,
    val provider: String,
)
