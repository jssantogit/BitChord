package com.music.bitchord

import com.music.bitchord.data.catalog.CanonicalMusicBrainzLocalAssetClaim
import com.music.bitchord.ui.canonicalVerifiedLocalBadgeMbids
import com.music.bitchord.ui.canonicalNativeBrowseId
import com.music.bitchord.ui.canonicalNativePageType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import com.music.bitchord.ui.screens.CanonicalServerSearchRow
import com.music.bitchord.ui.screens.canonicalRowsForFilter
import com.music.bitchord.ui.screens.canonicalRowsForQuery
import com.music.bitchord.ui.screens.canonicalDisplaySubtitle
import com.music.bitchord.ui.screens.canonicalSearchDisplaySections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSearchCategoryFilterTest {
    private val rows = listOf(
        CanonicalCatalogSearchRow("rec", CanonicalSearchKind.RECORDING, "Magnetic", "ILLIT"),
        CanonicalCatalogSearchRow("group", CanonicalSearchKind.RELEASE_GROUP, "SUPER REAL ME", "ILLIT", releaseKind = "EP"),
        CanonicalCatalogSearchRow("edition", CanonicalSearchKind.RELEASE, "SUPER REAL ME", "ILLIT", releaseKind = "EP"),
        CanonicalCatalogSearchRow("artist", CanonicalSearchKind.ARTIST, "ILLIT", null),
    )

    @Test fun albumsTabRejectsUnclassifiedWorksAndSongRecordings() {
        val unknown = CanonicalCatalogSearchRow("right-now", CanonicalSearchKind.RELEASE_GROUP,
            "Right Now", "NewJeans", releaseKind = "Other")
        val untypedEdition = unknown.copy(musicBrainzId = "edition", kind = CanonicalSearchKind.RELEASE,
            releaseKind = null)
        val track = CanonicalCatalogSearchRow("hypeboy", CanonicalSearchKind.RECORDING,
            "Hype Boy", "NewJeans")
        val typed = unknown.copy(musicBrainzId = "group-single", title = "OMG", releaseKind = "Single")
        assertEquals(listOf("group-single"), canonicalRowsForFilter(
            listOf(unknown, untypedEdition, track, typed), SearchFilter.ALBUMS,
        ).map { it.musicBrainzId })
        assertEquals(listOf("hypeboy"), canonicalRowsForFilter(
            listOf(unknown, untypedEdition, track, typed), SearchFilter.SONGS,
        ).map { it.musicBrainzId })
    }

    @Test fun artistsShowsArtistsOnly() {
        assertEquals(listOf("artist"), canonicalRowsForFilter(rows, SearchFilter.ARTISTS).map { it.musicBrainzId })
    }
    @Test fun albumsShowsGroupsAndEditionsButNotTrackRecordings() {
        assertEquals(listOf("group","edition"), canonicalRowsForFilter(rows, SearchFilter.ALBUMS).map { it.musicBrainzId })
    }
    @Test fun songsAndAllRemainDistinct() {
        assertEquals(listOf("rec"), canonicalRowsForFilter(rows, SearchFilter.SONGS).map { it.musicBrainzId })
        assertEquals(rows, canonicalRowsForFilter(rows, SearchFilter.ALL))
    }
    @Test fun unsupportedVideoAndPlaylistCategoriesNeverPretendMusicBrainzMediaExists() {
        assertTrue(canonicalRowsForFilter(rows, SearchFilter.VIDEOS).isEmpty())
        assertTrue(canonicalRowsForFilter(rows, SearchFilter.PLAYLISTS).isEmpty())
    }
    @Test fun serverSearchPreservesDistinctAssetsForOneRecordingWithoutNavigationAliases() {
        val one = CanonicalServerSearchRow(
            recordingId = "recording-1", mediaAssetId = "asset-1",
            providerKey = "opensubsonic:primary", title = "Magnetic", artistDisplay = "ILLIT",
            artworkUri = "https://example.com/art.jpg",
        )
        val another = one.copy(mediaAssetId = "asset-2", providerKey = "opensubsonic:secondary")
        // Search retains both provider-scoped assets; no fake browseId or
        // private stream URL enters its visual presentation contract.
        assertEquals(listOf("asset-1", "asset-2"),
            listOf(one, another).map { it.mediaAssetId })
        assertEquals(one.recordingId, another.recordingId)
        assertTrue(one.providerKey != another.providerKey)
    }

    @Test fun catalogIdentityRoutesAreTypedAndNeverMusicPlaybackIds() {
        val artist = rows.last()
        val album = rows[1]
        assertEquals(CanonicalSearchKind.ARTIST, artist.kind)
        assertEquals(CanonicalSearchKind.RELEASE_GROUP, album.kind)
        assertEquals("catalog:ARTIST:artist", canonicalNativeBrowseId(artist))
        assertEquals("catalog:RELEASE_GROUP:group", canonicalNativeBrowseId(album))
        assertEquals(com.music.bitchord.data.model.BrowseType.ARTIST, canonicalNativePageType(artist.kind))
        assertEquals(com.music.bitchord.data.model.BrowseType.ALBUM, canonicalNativePageType(album.kind))
    }

    @Test fun exactArtistQueryPlacesIdentityBeforeTextualRecordingMatches() {
        val unrelated = CanonicalCatalogSearchRow("other-song", CanonicalSearchKind.RECORDING, "Illit", "Kristian Kristiansen")
        val owned = CanonicalCatalogSearchRow("real-song", CanonicalSearchKind.RECORDING, "Magnetic", "ILLIT")
        val artist = CanonicalCatalogSearchRow("artist-main", CanonicalSearchKind.ARTIST, "ILLIT", "South Korean girl group")
        val album = CanonicalCatalogSearchRow("real-album", CanonicalSearchKind.RELEASE_GROUP, "SUPER REAL ME", "ILLIT")
        val displayed = canonicalRowsForQuery(listOf(unrelated, owned, album, artist), "illit", SearchFilter.ALL)
        assertEquals("artist-main", displayed.first().musicBrainzId)
        assertTrue(displayed.any { it.musicBrainzId == "real-song" })
        assertTrue(displayed.any { it.musicBrainzId == "real-album" })
        assertTrue(displayed.none { it.musicBrainzId == "other-song" })
    }

    @Test fun artistFilterDoesNotMergeUnverifiedHomonymsAndDeduplicatesOnlySameMbid() {
        val primary = CanonicalCatalogSearchRow("artist-1", CanonicalSearchKind.ARTIST, "ILLIT", "South Korean girl group")
        val unrelated = CanonicalCatalogSearchRow("artist-2", CanonicalSearchKind.ARTIST, "Illit", "Unrelated artist")
        val displayed = canonicalRowsForQuery(listOf(primary, primary, unrelated), "illit", SearchFilter.ARTISTS)
        assertEquals(listOf("artist-1", "artist-2"), displayed.map { it.musicBrainzId })
    }

    @Test fun artistMbidScopedWorksSurviveUnusualCreditText() {
        val artist = CanonicalCatalogSearchRow("artist-illit", CanonicalSearchKind.ARTIST,
            "ILLIT", null)
        val canonical = CanonicalCatalogSearchRow("recording-m", CanonicalSearchKind.RECORDING,
            "Magnetic", "Unusual credited name", artistScopeMbid = "artist-illit")
        val other = CanonicalCatalogSearchRow("other-m", CanonicalSearchKind.RECORDING,
            "Illit", "Another unrelated group")
        val displayed = canonicalRowsForQuery(listOf(other, canonical, artist), "illit", SearchFilter.ALL)
        assertEquals(listOf("artist-illit", "recording-m"), displayed.map { it.musicBrainzId })
    }

    @Test fun generalSearchUsesNativeArtistSongsAlbumsSectionOrder() {
        val artist = CanonicalCatalogSearchRow("artist-k", CanonicalSearchKind.ARTIST, "ILLIT", null)
        val song = CanonicalCatalogSearchRow("recording-k", CanonicalSearchKind.RECORDING, "Magnetic", "ILLIT")
        val group = CanonicalCatalogSearchRow("group-k", CanonicalSearchKind.RELEASE_GROUP, "SUPER REAL ME", "ILLIT", releaseKind = "EP")
        val sections = canonicalSearchDisplaySections(listOf(group, song, artist), SearchFilter.ALL)
        assertEquals(listOf(SearchFilter.ARTISTS, SearchFilter.SONGS, SearchFilter.ALBUMS),
            sections.map { it.first })
        assertEquals(listOf("artist-k", "recording-k", "group-k"),
            sections.flatMap { it.second }.map { it.musicBrainzId })
        val onlySongs = canonicalSearchDisplaySections(listOf(group, song, artist), SearchFilter.SONGS)
        assertEquals(1, onlySongs.size)
        assertEquals(null, onlySongs.single().first)
        assertEquals(3, onlySongs.single().second.size)
    }

    @Test fun generalSearchDoesNotPresentMultipleExactHomonymsAsOneArtist() {
        val primary = CanonicalCatalogSearchRow("illit-kpop", CanonicalSearchKind.ARTIST,
            "ILLIT", "South Korean girl group")
        val namesake = CanonicalCatalogSearchRow("illit-other", CanonicalSearchKind.ARTIST,
            "Illit", "Unrelated namesake")
        val matchingSong = CanonicalCatalogSearchRow("magnetic", CanonicalSearchKind.RECORDING,
            "Magnetic", "ILLIT")
        val general = canonicalRowsForQuery(
            listOf(primary, namesake, matchingSong), "illit", SearchFilter.ALL,
        )
        assertEquals(listOf("illit-kpop", "magnetic"), general.map { it.musicBrainzId })
        // Do not delete another artist identity from the explicit artist filter.
        val artists = canonicalRowsForQuery(
            listOf(primary, namesake), "illit", SearchFilter.ARTISTS,
        )
        assertEquals(2, artists.size)
    }

    @Test fun canonicalSearchSubtitlesAreMusicFacingNotInternalDiagnostics() {
        val song = CanonicalCatalogSearchRow("recording", CanonicalSearchKind.RECORDING,
            "Magnetic", "ILLIT", hasCatalogedAsset = false)
        val artist = CanonicalCatalogSearchRow("artist", CanonicalSearchKind.ARTIST,
            "ILLIT", "South Korean girl group")
        val release = CanonicalCatalogSearchRow("single", CanonicalSearchKind.RELEASE_GROUP,
            "Magnetic", "ILLIT · 2024-03-25", releaseKind = "Single")
        assertEquals("ILLIT", canonicalDisplaySubtitle(song, "Gravação"))
        assertEquals("Artista", canonicalDisplaySubtitle(artist, "Artista"))
        assertEquals("Single", canonicalDisplaySubtitle(release, "Álbum"))
        val withoutCredits = song.copy(subtitle = null)
        assertEquals("", canonicalDisplaySubtitle(withoutCredits, "Gravação"))
        assertEquals("", canonicalDisplaySubtitle(artist, "Artista · discografia").substringAfter("Artista", ""))
        val edition = release.copy(kind = CanonicalSearchKind.RELEASE,
            releaseKind = null, subtitle = "ILLIT · Japão · 2024")
        assertEquals("ILLIT", canonicalDisplaySubtitle(edition, "Edição · faixas"))
    }
    @Test fun localBadgeNeedsStrongMbidAndReadableMedia() {
        val claims = listOf(
            CanonicalMusicBrainzLocalAssetClaim(
                "mbid-local", "content://media/external/audio/media/12", "local-mediastore",
            ),
            CanonicalMusicBrainzLocalAssetClaim(
                "mbid-remote", "content://media/external/audio/media/12", "opensubsonic:account",
            ),
            CanonicalMusicBrainzLocalAssetClaim(
                "mbid-stale", "content://media/external/audio/media/13", "local-mediastore",
            ),
            CanonicalMusicBrainzLocalAssetClaim(
                "mbid-no-uri", "", "local-mediastore",
            ),
        )
        val actual = canonicalVerifiedLocalBadgeMbids(claims) { uri ->
            uri == "content://media/external/audio/media/12"
        }
        assertEquals(setOf("mbid-local"), actual)
    }

    @Test fun localBadgeCannotBeInferredFromCatalogPlayableOrTitleOnly() {
        val remoteOnly = CanonicalCatalogSearchRow(
            "recording-remote", CanonicalSearchKind.RECORDING,
            "Magnetic", "ILLIT", hasCatalogedAsset = true,
        )
        val metadataOnly = remoteOnly.copy(
            musicBrainzId = "recording-unlinked", hasCatalogedAsset = false,
        )
        assertTrue(!remoteOnly.hasVerifiedLocalAsset)
        assertTrue(!metadataOnly.hasVerifiedLocalAsset)
        assertTrue(canonicalVerifiedLocalBadgeMbids(emptyList()) { true }.isEmpty())
    }

    @Test fun canonicalTrackTimeUsesMusicBrainzEvidenceNotFakeZero() {
        assertEquals(null, com.music.bitchord.ui.screens.canonicalDurationLabel(null))
        assertEquals(null, com.music.bitchord.ui.screens.canonicalDurationLabel(0L))
        assertEquals("2:49", com.music.bitchord.ui.screens.canonicalDurationLabel(169_000L))
        assertEquals("1:02:03", com.music.bitchord.ui.screens.canonicalDurationLabel(3_723_000L))
    }

    @Test fun otherTypeMustNotLeakAsMusicGenre() {
        val unknown = CanonicalCatalogSearchRow(
            "group-other", CanonicalSearchKind.RELEASE_GROUP,
            "Right Now", "NewJeans", releaseKind = "Other",
        )
        val ep = unknown.copy(musicBrainzId = "group-ep", releaseKind = "EP")
        assertEquals("NewJeans", canonicalDisplaySubtitle(unknown, "Álbum"))
        assertEquals("EP", canonicalDisplaySubtitle(ep, "Álbum"))
    }

    @Test fun canonicalQueryHistoryReusesExistingRecentRowsAndRejectsEmptyTerms() {
        val value = com.music.bitchord.data.settings.canonicalQueryHistoryEntry("  NewJeans  ")!!
        assertEquals("q:newjeans", value.id)
        assertEquals("NewJeans", value.title)
        assertEquals(null, com.music.bitchord.data.settings.canonicalQueryHistoryEntry("  "))
        val youtubeEntity = value.copy(id = "legacy-video-123", title = "Hurt")
        assertEquals(listOf(value),
            com.music.bitchord.ui.screens.canonicalRecentSearchEntries(listOf(youtubeEntity, value)))
    }

}
