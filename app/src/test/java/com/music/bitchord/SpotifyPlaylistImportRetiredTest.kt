package com.music.bitchord

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/** Guard the deliberate retirement of importing Spotify playlists. */
class SpotifyPlaylistImportRetiredTest {
    @Test fun libraryCannotPresentOrOpenSpotifyPlaylistImporter() {
        val repoRoot = generateSequence(File(System.getProperty("user.dir")).canonicalFile) {
            it.parentFile
        }.firstOrNull {
            File(it, "app/src/main/java/com/music/bitchord/MainActivity.kt").isFile &&
                File(it, "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/LibraryScreen.kt").isFile
        } ?: error("Repository root not found for retirement contract")

        val host = File(repoRoot, "app/src/main/java/com/music/bitchord/MainActivity.kt").readText()
        val library = File(repoRoot,
            "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/LibraryScreen.kt",
        ).readText()

        assertFalse("No importer launcher state may survive in the host",
            host.contains("showSpotifyImportDialog"))
        assertFalse("The host must not mount the retired import sheet",
            host.contains("SpotifyImportAlert"))
        assertFalse("Library must not expose an import callback",
            library.contains("onImportSpotifyPlaylist"))
        assertFalse("Library must not render the Spotify import tile",
            library.contains("Res.string.import_spotify"))
    }
}
