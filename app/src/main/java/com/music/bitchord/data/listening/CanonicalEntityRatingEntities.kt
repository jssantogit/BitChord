package com.music.bitchord.data.listening

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The rating target is a verified catalog ArtistId or ReleaseId.
 * A display-only artist name never appears here, and recording ratings are
 * deliberately stored in independent recording_rating_* tables.
 */
@Entity(tableName = "canonical_entity_rating", primaryKeys = ["entityType", "entityId"])
internal data class CanonicalEntityRatingEntity(
    val entityType: String,
    val entityId: String,
    val ratingValue: Double,
    val ratedAtEpochMs: Long,
)

@Dao
internal interface CanonicalEntityRatingDao {
    @Query("""
        SELECT * FROM canonical_entity_rating
        WHERE entityType = :entityType AND entityId = :entityId LIMIT 1
    """)
    fun observe(entityType: String, entityId: String): Flow<CanonicalEntityRatingEntity?>

    @Query("""
        SELECT * FROM canonical_entity_rating
        WHERE entityType = :entityType AND entityId = :entityId LIMIT 1
    """)
    suspend fun current(entityType: String, entityId: String): CanonicalEntityRatingEntity?

    @Upsert
    suspend fun upsert(value: CanonicalEntityRatingEntity)
}
