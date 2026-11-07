package com.music.bitchord.data.canvas

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisualStillFileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun downloadedStillSurvivesNewStoreInstanceAndOfflineLookup() = runBlocking {
        val folder = temp.newFolder("visuals")
        var networkRequests = 0
        val save = VisualStillFileStore(folder, downloader = { _, into ->
            networkRequests++
            into.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            true
        })
        val remote = "https://images.example/artist.jpg"
        val first = save.ensure(VisualSurface.ARTIST, "AtHeart", remote)
        assertTrue(first != null && first.exists())
        val coldRestart = VisualStillFileStore(folder, downloader = { _, _ ->
            networkRequests++
            false
        })
        assertEquals(first!!.absolutePath,
            coldRestart.cached(VisualSurface.ARTIST, "AtHeart")?.absolutePath)
        assertEquals(1, networkRequests)
    }

    @Test fun incompleteOrMaliciousDownloadNeverBecomesAnOfflineImage() = runBlocking {
        val folder = temp.newFolder("atomic")
        val save = VisualStillFileStore(folder, downloader = { _, output ->
            output.writeBytes(byteArrayOf(1, 2))
            false
        })
        assertNull(save.ensure(VisualSurface.ARTIST, "ILLIT",
            "https://images.example/fail.jpg"))
        assertNull(save.cached(VisualSurface.ARTIST, "ILLIT"))
        assertFalse(folder.listFiles().orEmpty().any { it.extension == "part" })
        assertNull(save.ensure(VisualSurface.ARTIST, "ILLIT",
            "file:///data/data/private.jpg"))
    }

    @Test fun cacheKeysCannotCollideAcrossArtistAlbumAndPlayback() = runBlocking {
        val folder = temp.newFolder("independent")
        val save = VisualStillFileStore(folder, downloader = { _, f ->
            f.writeBytes(byteArrayOf(1, 2, 3)); true
        })
        save.ensure(VisualSurface.ALBUM, "Say It", "https://img.example/one.jpg")
        assertNull(save.cached(VisualSurface.ARTIST, "Say It"))
        assertNull(save.cached(VisualSurface.NOW_PLAYING, "Say It"))
        assertTrue(save.cached(VisualSurface.ALBUM, "Say It")!!.exists())
        save.clear()
        assertNull(save.cached(VisualSurface.ALBUM, "Say It"))
    }
}
