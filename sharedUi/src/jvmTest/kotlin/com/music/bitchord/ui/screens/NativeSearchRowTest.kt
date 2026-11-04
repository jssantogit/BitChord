package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonical search must display through a source-neutral native row without
 * manufacturing BrowseItem, YouTube browseId or Song.videoId.
 */
class NativeSearchRowTest {
    @Test fun recordingWithoutVerifiedLocalMediaRemainsMetadataOnly() {
        val row = CanonicalCatalogSearchRow(
            musicBrainzId = "recording-mbid", kind = CanonicalSearchKind.RECORDING,
            title = "Armageddon", subtitle = "aespa", durationMs = 185000L,
        )
        val visual = canonicalNativeSearchRowUi(row, "Recording")
        assertEquals("Armageddon", visual.title)
        assertEquals("aespa", visual.subtitle)
        assertEquals("3:05", visual.durationLabel)
        assertFalse(visual.artistPortrait)
        assertFalse(visual.downloadBadge)
    }

    @Test fun searchArtistUsesCircularArtworkAndNoFakeDownloadBadge() {
        val row = CanonicalCatalogSearchRow(
            musicBrainzId = "artist-mbid", kind = CanonicalSearchKind.ARTIST,
            title = "aespa", subtitle = null, hasVerifiedLocalAsset = true,
        )
        val visual = canonicalNativeSearchRowUi(row, "Artista")
        assertTrue(visual.artistPortrait)
        assertFalse(visual.downloadBadge)
        assertEquals("Artista", visual.subtitle)
    }

    @Test fun unverifiedGroupNeverClaimsThatAnotherEditionCoverIsOfficial() {
        val row = CanonicalCatalogSearchRow(
            musicBrainzId = "group-mbid", kind = CanonicalSearchKind.RELEASE_GROUP,
            title = "Unknown", subtitle = "aespa",
            releaseKind = null, artworkUri = "https://example.org/cover.jpg",
        )
        assertNull(canonicalNativeSearchRowUi(row, "Álbum").artworkUri)
        val classified = row.copy(releaseKind = "Album")
        assertEquals("https://example.org/cover.jpg",
            canonicalNativeSearchRowUi(classified, "Álbum").artworkUri)
    }

    @Test fun serverAssetPresentationCarriesNoPrivateStreamUrl() {
        val row = CanonicalServerSearchRow(
            recordingId = "recording", mediaAssetId = "asset",
            providerKey = "opensubsonic:server", title = "Track", artistDisplay = "Artist",
        )
        val visual = canonicalServerNativeSearchRowUi(row)
        assertEquals("Track", visual.title)
        assertEquals("Artist", visual.subtitle)
        assertFalse(visual.artistPortrait)
        assertFalse(visual.downloadBadge)
        assertNull(visual.artworkUri)
    }
}
