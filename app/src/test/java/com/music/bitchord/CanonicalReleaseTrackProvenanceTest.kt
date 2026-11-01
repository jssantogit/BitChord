package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CatalogReleaseTrackSourceEntity
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

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CanonicalReleaseTrackProvenanceTest {
    @Test fun localRescanCannotPruneRemoteOrMusicBrainzOwnedReleaseEdges() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("rec", "Magnetic", "ILLIT", 160000))
            for (release in listOf("local", "remote", "mixed")) {
                dao.upsertRelease(ReleaseEntity(release, "SUPER REAL ME", "ILLIT", null))
                dao.upsertReleaseTrack(ReleaseTrackEntity(release, "rec", 2))
                dao.upsertExternalIdentity(ExternalIdentityEntity("local-tags","release","local-$release",release))
            }
            dao.upsertCatalogReleaseTrackSource(CatalogReleaseTrackSourceEntity("local","rec","local-tags"))
            dao.upsertCatalogReleaseTrackSource(CatalogReleaseTrackSourceEntity("remote","rec","opensubsonic:home"))
            dao.upsertCatalogReleaseTrackSource(CatalogReleaseTrackSourceEntity("mixed","rec","local-tags"))
            dao.upsertCatalogReleaseTrackSource(CatalogReleaseTrackSourceEntity("mixed","rec","musicbrainz"))
            dao.deleteReleaseTrackSourcesForRecording("rec", "local-tags")
            dao.deleteLocalOnlyReleaseTracksForRecording("rec", "local-tags")
            assertEquals(
                listOf("mixed", "remote"),
                dao.catalogReleaseTracksForRecording("rec").map { it.releaseId },
            )
            assertEquals(listOf("musicbrainz"),
                dao.catalogReleaseTrackSources("mixed","rec").map { it.provider })
            assertEquals(listOf("opensubsonic:home"),
                dao.catalogReleaseTrackSources("remote","rec").map { it.provider })
        } finally { db.close() }
    }
}
