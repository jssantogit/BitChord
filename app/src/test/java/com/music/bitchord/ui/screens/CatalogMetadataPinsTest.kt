package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CatalogMetadataPinsTest {
    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val release = CatalogMetadataPin(
        "catalog:RELEASE_GROUP:$id", "STAY ALIVE", "STAYC",
        "https://coverartarchive.org/release/$id/front-250",
    )

    @Test fun pinCatalogItemWithoutPretendingItIsAnAvailableMediaAsset() {
        assertTrue(validCatalogMetadataPin(release))
        val saved = toggleCatalogMetadataPin(emptyList(), release)
        assertEquals(listOf(release), decodeCatalogMetadataPins(encodeCatalogMetadataPins(saved)))
        assertTrue(toggleCatalogMetadataPin(saved, release).isEmpty())
    }

    @Test fun refuseFakeIdsAndUntrustedCoverUris() {
        assertFalse(validCatalogMetadataPin(release.copy(browseId = "catalog:RELEASE_GROUP:STAYC")))
        assertFalse(validCatalogMetadataPin(release.copy(artworkUrl = "file:///private/song.mp3")))
        assertFalse(validCatalogMetadataPin(release.copy(artworkUrl = "https://evil.example/a.png")))
        assertFalse(validCatalogMetadataPin(release.copy(artworkUrl = "https://coverartarchive.org@evil.example/a")))
    }
    @Test fun pinnedReleaseCanOpenAlbumWithoutCurrentMusicBrainzSearchResults() {
        val target = catalogMetadataPinNativeTarget(release)
        assertEquals(release.browseId, target?.browseId)
        assertEquals("STAY ALIVE", target?.title)
        assertEquals("STAYC", target?.subtitle)
        assertEquals(com.music.bitchord.data.model.BrowseType.ALBUM, target?.type)
    }

    @Test fun pinnedArtistOpensArtistDetailAndRejectsFakeIdentities() {
        val artist = release.copy(
            browseId = "catalog:ARTIST:$id",
            title = "STAYC",
            subtitle = "",
            artworkUrl = null,
        )
        assertEquals(com.music.bitchord.data.model.BrowseType.ARTIST,
            catalogMetadataPinNativeTarget(artist)?.type)
        assertEquals(null, catalogMetadataPinNativeTarget(
            release.copy(browseId = "catalog:RELEASE_GROUP:STAY ALIVE"),
        ))
    }

}
