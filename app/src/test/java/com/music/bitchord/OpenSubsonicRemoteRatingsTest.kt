package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import com.music.bitchord.data.catalog.provider.RemoteNumericRatingKind
import com.music.bitchord.data.catalog.provider.RemoteStarredKind
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCatalogSync
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRemoteRatingReader
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class OpenSubsonicRemoteRatingsTest {
    private fun provider(
        songs: String,
        scope: String = "scope-a",
        fail: Boolean = false,
    ): OpenSubsonicProvider = OpenSubsonicProvider(
        config = OpenSubsonicAccountConfig(
            accountId = "home",
            serverUrl = "https://music.example.test",
            auth = OpenSubsonicAuth.ApiKey("private-key"),
            ratingScopeId = scope,
        ),
        transport = object : OpenSubsonicTransport {
            override suspend fun get(endpoint: String, parameters: Map<String, String>): String {
                assertEquals("json", parameters["f"])
                if (fail) return """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"bad password"}}}"""
                return when (endpoint) {
                    "getAlbumList2" -> """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"album"}]}}}"""
                    "getAlbum" -> """{"subsonic-response":{"status":"ok","album":{"id":"album","songCount":4,"song":[__SONGS__]}}}"""
                        .replace("__SONGS__", songs)
                    else -> error("Unexpected endpoint")
                }
            }
        },
    )

    private val songs = """
        {"id":"rated","title":"A","userRating":4,"starred":"2026-10-21T12:00:00Z"},
        {"id":"cleared","title":"B","userRating":0,"starred":false},
        {"id":"omitted","title":"C"},
        {"id":"invalid","title":"D","userRating":99,"starred":"not-a-time"}
    """.trimIndent()

    private fun account(scope: String) = OpenSubsonicAccountRecord(
        accountId = "home",
        serverUrl = "https://music.example.test",
        authMode = OpenSubsonicAuthMode.API_KEY,
        ratingScopeId = scope,
    )

    @Test fun jsonProtocolSeparatesValueZeroAbsentInvalidAndStarred() = runBlocking {
        val tracks = provider(songs).librarySnapshot().tracks.associateBy { it.externalTrackId }
        assertEquals(RemoteNumericRatingKind.VALUE, tracks.getValue("rated").remoteRating.numeric.kind)
        assertEquals(4, tracks.getValue("rated").remoteRating.numeric.value)
        assertEquals(RemoteStarredKind.STARRED, tracks.getValue("rated").remoteRating.starred)
        assertEquals(RemoteNumericRatingKind.CLEARED_ZERO, tracks.getValue("cleared").remoteRating.numeric.kind)
        assertNull(tracks.getValue("cleared").remoteRating.numeric.value)
        assertEquals(RemoteStarredKind.NOT_STARRED, tracks.getValue("cleared").remoteRating.starred)
        assertEquals(RemoteNumericRatingKind.NOT_PROVIDED, tracks.getValue("omitted").remoteRating.numeric.kind)
        assertEquals(RemoteStarredKind.NOT_PROVIDED, tracks.getValue("omitted").remoteRating.starred)
        assertEquals(RemoteNumericRatingKind.INVALID, tracks.getValue("invalid").remoteRating.numeric.kind)
        assertEquals(RemoteStarredKind.INVALID, tracks.getValue("invalid").remoteRating.starred)
    }

    @Test fun completedSyncMakesOnlyCanonicalBoundReadOnlyRemoteEvidenceAvailable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            assertTrue(OpenSubsonicCatalogSync.sync(db, provider(songs)) is ProviderSyncResult.Success)
            var active: OpenSubsonicAccountRecord? = account("scope-a")
            val reader = OpenSubsonicRemoteRatingReader(db) { active }
            val first = reader.read()
            assertEquals(RemoteRatingAvailability.CACHED_COMPLETE, first.availability)
            assertEquals(4, first.coverage.enumerated)
            assertEquals(4, first.coverage.matched)
            assertEquals(1, first.coverage.invalidNumeric)
            assertEquals(2, first.items.size)
            val rated = first.items.single { it.numeric.value == 4 }
            val actualAsset = db.catalogDao().mediaAsset("opensubsonic:home", "rated")
            assertNotNull(actualAsset)
            assertEquals(actualAsset?.id, rated.mediaAssetId)
            assertEquals(actualAsset?.recordingId, rated.recordingId)
            assertEquals(RemoteStarredKind.STARRED, rated.starred)
            assertFalse(rated.recordingId == "rated")

            // Account switch and logout must revoke visibility even when Room persists.
            active = account("scope-b")
            assertEquals(RemoteRatingAvailability.UNAVAILABLE, reader.read().availability)
            assertTrue(reader.read().items.isEmpty())
            active = null
            assertEquals(RemoteRatingAvailability.UNAVAILABLE, reader.read().availability)

            // Network is not touched by the offline reader.
            active = account("scope-a")
            assertEquals(2, reader.read().items.size)
        } finally { db.close() }
    }

    @Test fun authFailureDoesNotClearLastCompleteRemoteSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            assertTrue(OpenSubsonicCatalogSync.sync(db, provider(songs)) is ProviderSyncResult.Success)
            assertTrue(OpenSubsonicCatalogSync.sync(db, provider(songs, fail = true))
                is ProviderSyncResult.Failure)
            val reader = OpenSubsonicRemoteRatingReader(db) { account("scope-a") }
            assertEquals(2, reader.read().items.size)
            assertEquals(4, reader.read().coverage.enumerated)
        } finally { db.close() }
    }
}
