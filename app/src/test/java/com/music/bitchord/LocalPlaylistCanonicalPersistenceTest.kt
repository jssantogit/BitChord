package com.music.bitchord

import android.app.Application
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylistStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Local playlists are stored on device, not necessarily playable without
 * YouTube. Real local content URIs must nevertheless survive the playlist
 * persistence boundary without being silently downgraded to videoId-only rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocalPlaylistCanonicalPersistenceTest {
    private val app: Application
        get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        app.getSharedPreferences("bitchord_local_playlists", 0).edit().clear().commit()
        LocalPlaylistStore.init(app)
    }

    @Test fun savesRealLocalContentUriAcrossRestartWithoutConvertingToYoutube() {
        val uri = "content://media/external/audio/media/140"
        val original = Song(
            videoId = uri,
            title = "Magnetic (local file)",
            artist = "ILLIT",
            thumbnailUrl = null,
            localUri = uri,
        )
        val stored = LocalPlaylistStore.savePlaylist("Offline", listOf(original))
        LocalPlaylistStore.init(app) // reload from actual SharedPreferences

        val restored = LocalPlaylistStore.getPlaylist(stored.browseId)
        assertEquals(1, restored?.songs?.size)
        assertEquals(uri, restored?.songs?.single()?.videoId)
        assertEquals(uri, restored?.songs?.single()?.localUri)
        assertEquals("Magnetic (local file)", restored?.songs?.single()?.title)
    }

    @Test fun oldPlaylistWithLocalContentIdRecoversUriWithoutInventingAsset() {
        val uri = "content://media/external/audio/media/141"
        val legacy = JSONObject()
            .put("id", "legacy-offline")
            .put("title", "Old local")
            .put("songs", JSONArray().put(JSONObject()
                .put("videoId", uri)
                .put("title", "Magnetic")
                .put("artist", "ILLIT")))
        app.getSharedPreferences("bitchord_local_playlists", 0).edit()
            .putString("playlists_json", JSONArray().put(legacy).toString()).commit()

        LocalPlaylistStore.init(app)
        assertEquals(uri, LocalPlaylistStore.getPlaylist("legacy-offline")?.songs?.single()?.localUri)
    }

    @Test fun youtubeIdsAndRemoteWebUrlsNeverBecomeFabricatedLocalUris() {
        LocalPlaylistStore.savePlaylist("Mixed", listOf(
            Song("youtube-legacy-id", "Old stream", "Artist", null),
            Song("https://remote.invalid/stream", "Remote stream", "Artist", null),
        ))
        LocalPlaylistStore.init(app)
        val restored = LocalPlaylistStore.playlists.value.single().songs
        assertNull(restored[0].localUri)
        assertNull(restored[1].localUri)
        assertEquals("youtube-legacy-id", restored[0].videoId)
    }

    @Test fun reloadAfterStoreClearedDropsStaleInMemoryPlaylists() {
        LocalPlaylistStore.savePlaylist("Temporary", listOf(
            Song("old-youtube-id", "Old song", "Artist", null),
        ))
        assertTrue(LocalPlaylistStore.playlists.value.isNotEmpty())

        app.getSharedPreferences("bitchord_local_playlists", 0).edit().clear().commit()
        LocalPlaylistStore.init(app)
        assertTrue(LocalPlaylistStore.playlists.value.isEmpty())
    }
}
