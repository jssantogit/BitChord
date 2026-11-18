package com.music.bitchord.ui.components

import com.music.bitchord.data.catalog.CanonicalArtistCreditRow
import com.music.bitchord.data.catalog.ArtistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerArtistEvidenceTest {
    private val credited = listOf(CanonicalArtistCreditRow("artist-1", "BTS", 0))
    private val artists = listOf(ArtistEntity(id = "artist-1", name = "BTS"))

    @Test fun sourceOwnedArtistUsesVerifiedIdAndSourceNotTitleGuess() {
        assertEquals(
            SourceOwnedArtistEvidence("artist-1", "BTS", "local-mediastore"),
            verifiedSourceOwnedPlayerArtist(
                "recording-1", "local-mediastore", credited, artists,
            ),
        )
        assertEquals(
            SourceOwnedArtistEvidence("artist-1", "BTS", "opensubsonic:account-1"),
            verifiedSourceOwnedPlayerArtist(
                "recording-1", "opensubsonic:account-1", credited, artists,
            ),
        )
    }

    @Test fun noArtistFromAnotherProviderOrAmbiguousLead() {
        assertNull(verifiedSourceOwnedPlayerArtist("recording-1", "local-mediastore", credited, emptyList()))
        assertNull(verifiedSourceOwnedPlayerArtist("recording-1", "local-mediastore",
            credited + CanonicalArtistCreditRow("artist-2", "Other", 0),
            artists + ArtistEntity(id = "artist-2", name = "Other")))
        assertNull(verifiedSourceOwnedPlayerArtist("recording-1", "", credited, artists))
    }

    @Test fun missingCreditsMayResolveOnlyUniqueArtistWithinVerifiedSource() {
        assertEquals(
            SourceOwnedArtistEvidence("artist-1", "BTS", "local-mediastore"),
            verifiedSourceOwnedPlayerArtist(
                "recording-1", "local-mediastore", emptyList(), artists,
                artistName = "BTS",
            ),
        )
        assertNull(verifiedSourceOwnedPlayerArtist(
            "recording-1", "local-mediastore", emptyList(),
            artists + ArtistEntity("artist-2", "BTS"), artistName = "BTS",
        ))
        assertNull(verifiedSourceOwnedPlayerArtist(
            "recording-1", "local-mediastore", emptyList(), artists,
            artistName = "Different",
        ))
        assertNull(verifiedSourceOwnedPlayerArtist(
            "recording-1", "local-mediastore", credited +
                CanonicalArtistCreditRow("artist-2", "BTS", 0),
            artists, artistName = "BTS",
        ))
    }

    @Test fun uncreditedLocalArtistOpensOnlyItsOwnVerifiedDisplayGroup() {
        val local = "local-mediastore"
        val expected = SourceOwnedArtistEvidence(
            "browse-display-artist:SAY MY NAME", "SAY MY NAME", local,
        )
        assertEquals(expected, verifiedLocalDisplayArtist(
            "recording-1", local, "SAY MY NAME", listOf("recording-1"),
        ))
        assertNull(verifiedLocalDisplayArtist(
            "other-recording", local, "SAY MY NAME", listOf("recording-1"),
        ))
        assertNull(verifiedLocalDisplayArtist(
            "recording-1", "opensubsonic:other", "SAY MY NAME", listOf("recording-1"),
        ))
        assertNull(verifiedLocalDisplayArtist(
            "recording-1", local, "SAY MY NAME ", listOf("recording-1"),
        ))
    }

    @Test fun localMediaStoreAssetOpensArtistEvenWhenQueueIdentityIsUnavailable() {
        val url = "content://media/external/audio/media/45"
        val expected = SourceOwnedArtistEvidence(
            "browse-display-artist:BTS", "BTS", "local-mediastore",
        )
        assertEquals(expected, verifiedLocalMediaAssetArtist(
            requestedUri = url, verifiedSourceKey = url,
            recordingId = "rec-45", displayName = "BTS",
            memberRecordingIds = listOf("rec-45", "rec-46"),
        ))
        assertNull(verifiedLocalMediaAssetArtist(
            requestedUri = url, verifiedSourceKey = "content://media/other",
            recordingId = "rec-45", displayName = "BTS",
            memberRecordingIds = listOf("rec-45"),
        ))
        assertNull(verifiedLocalMediaAssetArtist(
            requestedUri = url, verifiedSourceKey = url,
            recordingId = "rec-45", displayName = "BTS",
            memberRecordingIds = emptyList(),
        ))
    }

    @Test fun localAlbumArtistCreditOpensOnlyAnExactSameSourceDisplayGroup() {
        val expected = SourceOwnedArtistEvidence(
            "browse-display-artist:BTS", "BTS", "local-mediastore",
        )
        assertEquals(expected, verifiedLocalAlbumDisplayArtist(
            providerKey = "local-mediastore", albumCredit = "BTS",
            memberArtistNames = listOf("BTS", "BTS"),
        ))
        assertNull(verifiedLocalAlbumDisplayArtist(
            providerKey = "opensubsonic:server", albumCredit = "BTS",
            memberArtistNames = listOf("BTS"),
        ))
        assertNull(verifiedLocalAlbumDisplayArtist(
            providerKey = "local-mediastore", albumCredit = "BTS",
            memberArtistNames = listOf("BTS", "Various Artists"),
        ))
        assertNull(verifiedLocalAlbumDisplayArtist(
            providerKey = "local-mediastore", albumCredit = "BTS",
            memberArtistNames = emptyList(),
        ))
    }

    @Test fun localPlayerArtistNeverOffersANameSearchInsteadOfLibraryNavigation() {
        assertFalse(shouldOfferFallbackArtistSearch(
            localUri = "content://media/external/audio/media/42",
            artist = "BTS",
        ))
        assertFalse(shouldOfferFallbackArtistSearch(
            localUri = "file:///Music/Proof.flac",
            artist = "BTS",
        ))
        assertTrue(shouldOfferFallbackArtistSearch(
            localUri = null, artist = "BTS",
        ))
        assertFalse(shouldOfferFallbackArtistSearch(
            localUri = null, artist = "  ",
        ))
    }

    @Test fun appleRouteIsNotInventedFromMetadataName() {
        assertEquals(
            "itunes:br:artist:123",
            verifiedAppleArtistBrowseRoute("itunes:br:artist:123"),
        )
        assertNull(verifiedAppleArtistBrowseRoute("itunes:br:album:123"))
        assertNull(verifiedAppleArtistBrowseRoute("123"))
        assertNull(verifiedAppleArtistBrowseRoute("itunes:br:artist:0"))
    }
    @Test fun onlyVerifiedLegacyArtistRoutesMayUseLegacyNavigation() {
        assertEquals("UC12345678901234567890", verifiedLegacyPlayerArtistBrowseId("UC12345678901234567890"))
        assertNull(verifiedLegacyPlayerArtistBrowseId("opensubsonic:account:artist-1"))
        assertNull(verifiedLegacyPlayerArtistBrowseId("artist-1"))
        assertNull(verifiedLegacyPlayerArtistBrowseId("itunes:br:artist:123"))
    }

    @Test fun tappingLocalArtistGoesToVerifiedLibraryGroupNotCatalogSearch() {
        val song = com.music.bitchord.data.model.Song(
            videoId = "content://media/external/audio/media/45",
            title = "Faixa local", artist = "BTS", thumbnailUrl = null,
            localUri = "content://media/external/audio/media/45",
        )
        val evidence = SourceOwnedArtistEvidence(
            "browse-display-artist:BTS", "BTS", "local-mediastore",
        )
        assertEquals(
            PlayerArtistTapRoute.SourceOwned(evidence),
            planPlayerArtistTapRoute(song, clickedId = null, clickedName = "BTS", sourceOwned = evidence),
        )
        assertNull(planPlayerArtistTapRoute(
            song, clickedId = null, clickedName = "BTS", sourceOwned = null,
        ))
        // A catalog artist ID attached to old Song metadata must not override
        // the actual local recording's provider provenance.
        assertNull(planPlayerArtistTapRoute(
            song.copy(artistId = "itunes:br:artist:123"),
            clickedId = "itunes:br:artist:123", clickedName = "BTS", sourceOwned = null,
        ))
    }

    @Test fun aSourceOwnedRemoteArtistStaysInsideItsServer() {
        val song = com.music.bitchord.data.model.Song(
            videoId = "bitchord://remote/song-5", title = "Faixa",
            artist = "BTS", thumbnailUrl = null,
        )
        val evidence = SourceOwnedArtistEvidence(
            "navidrome-artist-6", "BTS", "opensubsonic:account-1",
        )
        assertEquals(
            PlayerArtistTapRoute.SourceOwned(evidence),
            planPlayerArtistTapRoute(song, clickedId = null, clickedName = "BTS", sourceOwned = evidence),
        )
        // A different credited person must not be redirected to the lead artist.
        assertNull(planPlayerArtistTapRoute(
            song.copy(artist = "BTS, feat. Guest"),
            clickedId = null, clickedName = "Guest", sourceOwned = evidence,
        ))
    }

    @Test fun namedArtistWithoutVerifiedIdentityNeverSilentlyOpensOnlineSearch() {
        val localWithoutLocalUri = com.music.bitchord.data.model.Song(
            videoId = "content://media/external/audio/media/45",
            title = "Faixa", artist = "BTS", thumbnailUrl = null,
        )
        assertNull(planPlayerArtistTapRoute(
            localWithoutLocalUri, clickedId = null, clickedName = "BTS", sourceOwned = null,
        ))
        assertNull(planPlayerArtistTapRoute(
            localWithoutLocalUri, clickedId = "itunes:br:artist:123",
            clickedName = "BTS", sourceOwned = null,
        ))
        assertNull(planPlayerArtistTapRoute(
            com.music.bitchord.data.model.Song(
                videoId = "unknown", title = "Faixa", artist = "BTS", thumbnailUrl = null,
            ),
            clickedId = null, clickedName = "BTS", sourceOwned = null,
        ))
    }

    @Test fun exactCatalogArtistIdOpensOnlyMatchingCreditedArtist() {
        val apple = com.music.bitchord.data.model.Song(
            videoId = "catalog-track", title = "Faixa", artist = "BTS",
            thumbnailUrl = null, artistId = "itunes:br:artist:123",
        )
        assertEquals(
            PlayerArtistTapRoute.Catalog("itunes:br:artist:123", "BTS"),
            planPlayerArtistTapRoute(apple, "itunes:br:artist:123", "BTS", null),
        )
        assertNull(planPlayerArtistTapRoute(apple, "itunes:br:artist:789", "BTS", null))
        assertNull(planPlayerArtistTapRoute(apple, "itunes:br:artist:123", "Outro", null))
        assertNull(planPlayerArtistTapRoute(apple, null, "BTS", null))
        assertNull(planPlayerArtistTapRoute(apple.copy(artistId = "UC12345678901234567890"),
            "UC12345678901234567890", "BTS", null))
    }

    @Test fun collaborationMayOpenOnlyAnExplicitlyLinkedCatalogCredit() {
        val song = com.music.bitchord.data.model.Song(
            videoId = "catalog-track", title = "Dueto", artist = "BTS",
            thumbnailUrl = null, artistId = "itunes:br:artist:111",
            artists = listOf(
                com.music.bitchord.data.model.ArtistRef("BTS", "itunes:br:artist:111"),
                com.music.bitchord.data.model.ArtistRef("Convidada", "itunes:br:artist:222"),
            ),
        )
        assertEquals(
            PlayerArtistTapRoute.Catalog("itunes:br:artist:222", "Convidada"),
            planPlayerArtistTapRoute(song, "itunes:br:artist:222", "Convidada", null),
        )
        assertNull(planPlayerArtistTapRoute(song, null, "Convidada", null))
        assertNull(planPlayerArtistTapRoute(song, "itunes:br:artist:111", "Convidada", null))
    }

    @Test fun verifiedLeadCannotSilentlyStealAnotherCreditTap() {
        val song = com.music.bitchord.data.model.Song(
            videoId = "bitchord://remote/song-1", title = "Dueto",
            artist = "BTS, Convidada", thumbnailUrl = null,
        )
        val lead = SourceOwnedArtistEvidence("id-lead", "BTS", "opensubsonic:server")
        assertNull(planPlayerArtistTapRoute(
            song, "itunes:br:artist:222", "Convidada", lead,
        ))
    }

}
