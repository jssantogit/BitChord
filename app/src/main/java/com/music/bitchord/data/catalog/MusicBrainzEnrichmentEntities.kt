package com.music.bitchord.data.catalog

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Rebuildable MusicBrainz metadata cache. These rows enrich canonical entities;
 * they never replace local canonical IDs or base metadata.
 */
@Entity(
    tableName = "musicbrainz_artist_enrichment",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mbid")],
)
internal data class MusicBrainzArtistEnrichmentEntity(
    @PrimaryKey val artistId: String,
    val mbid: String,
    val name: String?,
    val sortName: String?,
    val disambiguation: String?,
    val country: String?,
    val area: String?,
    val fetchedAtEpochMs: Long,
)

@Entity(
    tableName = "musicbrainz_recording_enrichment",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mbid")],
)
internal data class MusicBrainzRecordingEnrichmentEntity(
    @PrimaryKey val recordingId: String,
    val mbid: String,
    val title: String?,
    val durationMs: Long?,
    val artistDisplay: String?,
    val fetchedAtEpochMs: Long,
    /** Positive audio-format evidence from MusicBrainz Recording search. */
    val hasOfficialAudioRelease: Boolean? = null,
    /** Distinguishes explicit video recordings from official studio audio. */
    val isVideo: Boolean? = null,
)

@Entity(
    tableName = "musicbrainz_recording_artist_credit",
    primaryKeys = ["recordingId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("musicBrainzArtistId")],
)
internal data class MusicBrainzRecordingArtistCreditEntity(
    val recordingId: String,
    val position: Int,
    val musicBrainzArtistId: String,
    val name: String,
    val joinPhrase: String?,
)

@Entity(
    tableName = "musicbrainz_release_enrichment",
    foreignKeys = [
        ForeignKey(
            entity = ReleaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["releaseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mbid")],
)
internal data class MusicBrainzReleaseEnrichmentEntity(
    @PrimaryKey val releaseId: String,
    val mbid: String,
    val title: String?,
    val artistDisplay: String?,
    val releaseDate: String?,
    val country: String?,
    val status: String?,
    val fetchedAtEpochMs: Long,
)

@Entity(
    tableName = "musicbrainz_release_artist_credit",
    primaryKeys = ["releaseId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ReleaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["releaseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("musicBrainzArtistId")],
)
internal data class MusicBrainzReleaseArtistCreditEntity(
    val releaseId: String,
    val position: Int,
    val musicBrainzArtistId: String,
    val name: String,
    val joinPhrase: String?,
)
