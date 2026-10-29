package com.music.bitchord.data.listening

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User-authored local rule only: neither provider playlist nor a frozen member
 * list. The L6 planner re-evaluates available canonical Recordings on demand.
 */
internal data class SavedSmartPlaylistDefinition(
    val id: String,
    val name: String,
    val query: RuleBasedSmartPlaylistQuery,
    val schemaVersion: Int = 1,
)

@Entity(tableName = "smart_playlist_definition")
internal data class SmartPlaylistDefinitionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val schemaVersion: Int,
    val minimumExplicitRating: Double?,
    val minimumCompletions: Long?,
    val minimumDaysSinceAudible: Long?,
    val maximumDaysSinceAudible: Long?,
    val minimumDurationMs: Long?,
    val limit: Int,
)

@Dao
internal interface SmartPlaylistDefinitionDao {
    @Upsert
    suspend fun upsert(definition: SmartPlaylistDefinitionEntity)

    @Query("SELECT * FROM smart_playlist_definition ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun all(): List<SmartPlaylistDefinitionEntity>

    @Query("SELECT * FROM smart_playlist_definition ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observe(): Flow<List<SmartPlaylistDefinitionEntity>>

    @Query("DELETE FROM smart_playlist_definition WHERE id = :id")
    suspend fun remove(id: String)
}

/**
 * Independent local database. We deliberately do NOT change ListeningEventDatabase,
 * its migrations, playback state, or provider-owned playlist storage.
 */
@Database(
    entities = [
        SmartPlaylistDefinitionEntity::class,
        SmartPlaylistV2DefinitionEntity::class,
        SmartPlaylistV2FilterEntity::class,
        CanonicalStaticPlaylistEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
internal abstract class SmartPlaylistDefinitionDatabase : RoomDatabase() {
    abstract fun definitionDao(): SmartPlaylistDefinitionDao
    abstract fun v2DefinitionDao(): SmartPlaylistV2DefinitionDao
    abstract fun v2FilterDao(): SmartPlaylistV2FilterDao
    abstract fun staticPlaylistDao(): CanonicalStaticPlaylistDao

    internal companion object {
        private const val DATABASE_NAME = "smart-playlist-definitions.db"

        // V1 rows are intentionally untouched: V1 users lose neither rules nor
        // names while V2 is introduced in a separate, versioned table.
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `smart_playlist_v2_definition` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        `queryJson` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }
        /** Filters are separate from playlist definitions and never rewrite V1/V2 rows. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `smart_playlist_v2_filter` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        `rulesJson` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }
        /** Canonical normal playlists are independent of smart rules and provider songs. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `canonical_static_playlist` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        `recordingIdsJson` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }
        /** V4 normal playlists default to all providers; no member is lost. */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE canonical_static_playlist ADD COLUMN sourceProviderKey TEXT"
                )
            }
        }
        @Volatile private var instance: SmartPlaylistDefinitionDatabase? = null

        fun get(context: Context): SmartPlaylistDefinitionDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SmartPlaylistDefinitionDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }
    }
}

/** Saves only validated versioned query definitions; all rows remain local. */
internal class SmartPlaylistDefinitionRepository(
    private val dao: SmartPlaylistDefinitionDao,
) {
    suspend fun save(definition: SavedSmartPlaylistDefinition) {
        require(definition.id.isNotBlank() && definition.id.length <= 100) {
            "Smart playlist must have a local definition ID"
        }
        require(definition.schemaVersion == 1) { "Unsupported rule schema" }
        val name = definition.name.trim()
        require(name.isNotBlank() && name.length <= 80) { "Playlist name must be 1..80 characters" }
        val query = definition.query
        // RuleBasedSmartPlaylistQuery already validates all supported AND rules.
        dao.upsert(SmartPlaylistDefinitionEntity(
            id = definition.id,
            name = name,
            schemaVersion = definition.schemaVersion,
            minimumExplicitRating = query.minimumExplicitRating,
            minimumCompletions = query.minimumCompletions,
            minimumDaysSinceAudible = query.minimumDaysSinceAudible,
            maximumDaysSinceAudible = query.maximumDaysSinceAudible,
            minimumDurationMs = query.minimumDurationMs,
            limit = query.limit,
        ))
    }

    suspend fun list(): List<SavedSmartPlaylistDefinition> = dao.all().map { it.toDomain() }

    fun observe(): Flow<List<SavedSmartPlaylistDefinition>> =
        dao.observe().map { rows -> rows.map { it.toDomain() } }

    suspend fun remove(id: String) {
        require(id.isNotBlank())
        dao.remove(id)
    }

    private fun SmartPlaylistDefinitionEntity.toDomain(): SavedSmartPlaylistDefinition {
        require(schemaVersion == 1 && id.isNotBlank() && name.isNotBlank()) {
            "Unsupported or corrupt local playlist definition"
        }
        return SavedSmartPlaylistDefinition(
            id = id, name = name, schemaVersion = schemaVersion,
            query = RuleBasedSmartPlaylistQuery(
                minimumExplicitRating = minimumExplicitRating,
                minimumCompletions = minimumCompletions,
                minimumDaysSinceAudible = minimumDaysSinceAudible,
                maximumDaysSinceAudible = maximumDaysSinceAudible,
                minimumDurationMs = minimumDurationMs,
                limit = limit,
            ),
        )
    }
}
