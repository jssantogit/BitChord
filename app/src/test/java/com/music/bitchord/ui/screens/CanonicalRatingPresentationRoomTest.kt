package com.music.bitchord.ui.screens

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * #179 regression: a one-shot assets query leaves badges stale after a local
 * import, even if the account and all rating rows remain unchanged.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CanonicalRatingPresentationRoomTest {
    private val local = "local-mediastore"
    private val remote = "opensubsonic:active"

    @Test
    fun localAssetImportsAndRemovalInvalidateVisibleRatingsWithoutRatingEdits() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("canonical-a", "Title A", "Artist", null))

            val emissions = kotlinx.coroutines.channels.Channel<List<String>>(capacity = 10)
            val observer = launch(Dispatchers.Default) {
                dao.observeRatingPresentationAssets(local, remote).collect { rows ->
                    emissions.send(rows.map { it.mediaAssetId })
                }
            }
            try {
                assertEquals(emptyList<String>(), withTimeout(10_000) { emissions.receive() })
                dao.upsertMediaAsset(MediaAssetEntity(
                    "asset-a", "canonical-a", local, "device-a", "content://media/music/a",
                    null, null, null, null,
                ))
                assertEquals(listOf("asset-a"), withTimeout(10_000) { emissions.receive() })

                dao.deleteMediaAsset(local, "device-a")
                assertEquals(emptyList<String>(), withTimeout(10_000) { emissions.receive() })
            } finally {
                observer.cancelAndJoin()
                emissions.close()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun scopedAssetFlowNeverReturnsAssetsFromAnotherNavidromeAccount() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("r", "Track", "Artist", null))
            dao.upsertMediaAsset(MediaAssetEntity(
                "remote-a", "r", remote, "native-a", "opensubsonic://a",
                null, null, null, null,
            ))
            dao.upsertMediaAsset(MediaAssetEntity(
                "remote-b", "r", "opensubsonic:foreign", "native-b", "opensubsonic://b",
                null, null, null, null,
            ))
            val visible = dao.observeRatingPresentationAssets(local, remote).first()
            assertEquals(listOf("remote-a"), visible.map { it.mediaAssetId })
            assertTrue(dao.observeRatingPresentationAssets(local, "opensubsonic:missing").first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun musicBrainzIdentityCarriesNativeRatingWhenMediaAssetsAreAttachedLater() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val mbid = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val otherMbid = "3e619ba2-34f0-4649-83d8-05483e2fabe5"
            var nextId = 0
            val index = com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex(db) {
                "canonical-recording-${++nextId}"
            }
            val original = com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit(
                mbid, "Magnetic", "ILLIT", 160_000L,
            )
            val alternative = original.copy(musicBrainzId = otherMbid, durationMs = 124_000L)
            val ids = index.persistRecordings(listOf(original, alternative))
            val recordingId = ids.getValue(mbid)
            val differentRecordingId = ids.getValue(otherMbid)
            org.junit.Assert.assertNotEquals(recordingId, differentRecordingId)

            val dao = db.catalogDao()
            assertTrue(dao.observeRatingPresentationAssets(local, remote).first().isEmpty())
            // The user-owned score belongs to the verified RecordingId, not
            // a MusicBrainz query string or a temporary asset/provider key.
            val native = mapOf(recordingId to 4.5, differentRecordingId to 2.0)
            dao.upsertMediaAsset(MediaAssetEntity(
                "local-asset", recordingId, local, "device-media",
                "content://media/music/magnetic", null, null, null, null,
            ))
            dao.upsertMediaAsset(MediaAssetEntity(
                "remote-asset", recordingId, remote, "navidrome-native",
                "opensubsonic://canonical", null, null, null, null,
            ))
            val assets = dao.observeRatingPresentationAssets(local, remote).first()
            val display = buildCanonicalRatingPresentation(
                assets = assets,
                localValues = native,
                remote = listOf(
                    CanonicalRemoteRatingInput(
                        recordingId, "remote-asset", remote, "active-scope", 3, false,
                    ),
                ),
                activeProvider = remote,
                activeScope = "active-scope",
            )
            assertEquals(4.5, display.byMediaAssetId.getValue("local-asset").localValue!!, 0.0)
            assertEquals(4.5, display.byMediaAssetId.getValue("remote-asset").localValue!!, 0.0)
            assertEquals(3, display.byMediaAssetId.getValue("remote-asset").remoteValue)
            assertEquals(4.5, display.byRecordingId.getValue(recordingId).localValue!!, 0.0)

            // Re-indexing the exact MBID changes neither canonical ownership
            // nor which RecordingId holds the native rating.
            assertEquals(recordingId, index.persistRecordings(listOf(original))[mbid])
            assertEquals(recordingId, dao.entityIdForExternalIdentity("musicbrainz", "recording", mbid))
            assertEquals(differentRecordingId, dao.entityIdForExternalIdentity(
                "musicbrainz", "recording", otherMbid,
            ))
        } finally {
            db.close()
        }
    }

}
