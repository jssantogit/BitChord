package com.music.bitchord.ui.screens

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingEntity
import kotlinx.coroutines.Channel
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
}
