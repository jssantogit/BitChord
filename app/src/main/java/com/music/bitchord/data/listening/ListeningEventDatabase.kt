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
    ],
    version = 2,
    exportSchema = false,
)
internal abstract class ListeningEventDatabase : RoomDatabase() {
    abstract fun listeningEventDao(): ListeningEventDao

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

        @Volatile
        private var instance: ListeningEventDatabase? = null

        fun get(context: Context): ListeningEventDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ListeningEventDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
