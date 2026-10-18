package com.music.bitchord.data.listening

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ListeningSessionEntity::class,
        ListeningEventEntity::class,
        RecordingRatingCurrentEntity::class,
        RecordingRatingHistoryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
internal abstract class ListeningEventDatabase : RoomDatabase() {
    abstract fun listeningEventDao(): ListeningEventDao
    abstract fun recordingRatingDao(): RecordingRatingDao

    internal companion object {
        const val DATABASE_NAME = "listening-events.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE listening_session ADD COLUMN lastActivityAtEpochMs INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    UPDATE listening_session
                    SET lastActivityAtEpochMs = COALESCE(
                        (SELECT MAX(occurredAtEpochMs)
                         FROM listening_event
                         WHERE listening_event.sessionId = listening_session.id),
                        startedAtEpochMs
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recording_rating_current (
                        recordingId TEXT NOT NULL,
                        ratingValue REAL NOT NULL,
                        ratedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(recordingId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recording_rating_history (
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        recordingId TEXT NOT NULL,
                        previousRatingValue REAL,
                        newRatingValue REAL,
                        changedAtEpochMs INTEGER NOT NULL,
                        operation TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_recording_rating_history_recordingId ON recording_rating_history(recordingId)",
                )
            }
        }

        @Volatile
        private var instance: ListeningEventDatabase? = null

        fun get(context: Context): ListeningEventDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ListeningEventDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
