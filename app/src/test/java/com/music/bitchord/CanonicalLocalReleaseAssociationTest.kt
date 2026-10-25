package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * A MediaStore rescan only owns local-tag associations. It cannot remove a
 * MusicBrainz or OpenSubsonic release association for the same Recording.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CanonicalLocalReleaseAssociationTest {
    @Test
    fun localRescanPrunesOnlyUnsharedLocalTags() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("recording", "Magnetic", "ILLIT", 160_000L))
            for (id in listOf("local-old", "remote", "shared", "unclaimed")) {
                dao.upsertRelease(ReleaseEntity(id, "SUPER REAL ME", "ILLIT", null))
                dao.upsertReleaseTrack(ReleaseTrackEntity(id, "recording", 2))
            }
            suspend fun claim(provider: String, release: String) {
                dao.upsertExternalIdentity(
                    ExternalIdentityEntity(provider, "release", "$provider-$release", release),
                )
            }
            claim("local-tags", "local-old")
            claim("opensubsonic:home", "remote")
            claim("local-tags", "shared")
            claim("musicbrainz", "shared")

            dao.deleteLocalOnlyReleaseTracksForRecording("recording", "local-tags")

            val result = mutableListOf<String>()
            db.openHelper.readableDatabase.query(
                "SELECT releaseId FROM catalog_release_track WHERE recordingId = 'recording' ORDER BY releaseId",
            ).use { cursor ->
                while (cursor.moveToNext()) result.add(cursor.getString(0))
            }
            assertEquals(listOf("remote", "shared", "unclaimed"), result)
        } finally {
            db.close()
        }
    }
}
