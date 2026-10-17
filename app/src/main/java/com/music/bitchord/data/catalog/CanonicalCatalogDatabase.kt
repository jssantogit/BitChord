package com.music.bitchord.data.catalog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecordingEntity::class,
        ReleaseEntity::class,
        ReleaseTrackEntity::class,
        MediaAssetEntity::class,
        ExternalIdentityEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
internal abstract class CanonicalCatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): CanonicalCatalogDao

    companion object {
        @Volatile
        private var instance: CanonicalCatalogDatabase? = null

        fun get(context: Context): CanonicalCatalogDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CanonicalCatalogDatabase::class.java,
                    "canonical-catalog.db",
                ).build().also { instance = it }
            }
    }
}
