package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualAlbumArtistFilterTest {
    private fun filter(rule: SmartPlaylistRule) =
        SavedSmartPlaylistV2Filter("f", "Album or artist", rule)

    @Test fun artistContextFiltersArtistIdentitiesNeverPretendsTheyAreRecordings() {
        val rows = listOf(ArtistEntity("artist:01", "IVE"),
            ArtistEntity("artist:02", "NewJeans"), ArtistEntity("artist:03", "TWICE"))
        val rule = SmartPlaylistRule.Comparison(SmartPlaylistRuleField.ARTIST_DISPLAY,
            SmartPlaylistRuleOperator.CONTAINS, textValue = "i")
        assertTrue(filter(rule).isSupportedForCanonicalArtistBrowse())
        assertEquals(listOf("artist:01", "artist:03"),
            filterCanonicalBrowseArtists(rows, filter(rule)).map { it.id })
        assertFalse(filter(SmartPlaylistRule.Comparison(SmartPlaylistRuleField.TITLE,
            SmartPlaylistRuleOperator.CONTAINS, textValue = "test"))
            .isSupportedForCanonicalArtistBrowse())
        assertThrows(IllegalArgumentException::class.java) {
            filterCanonicalBrowseArtists(rows, filter(SmartPlaylistRule.Reference("playlist:id")))
        }
    }

    @Test fun albumsFilterRealReleaseEntitiesNotReleaseTitleTextGrouping() {
        val rows = listOf(
            ReleaseEntity("release:one", "Get Up", "NewJeans", "https://example.org/a.jpg"),
            ReleaseEntity("release:two", "Get Up", "NewJeans", null),
            ReleaseEntity("release:three", "OMG", "NewJeans", "https://example.org/c.jpg"),
        )
        val title = SmartPlaylistRule.Comparison(SmartPlaylistRuleField.RELEASE_TITLE,
            SmartPlaylistRuleOperator.EQUAL, textValue = "get up")
        val reference = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE,
            SmartPlaylistRuleOperator.IS_PRESENT,
        )
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(title, reference))
        assertTrue(filter(rule).isSupportedForCanonicalAlbumBrowse())
        assertEquals(listOf("release:one"),
            filterCanonicalBrowseAlbums(rows, filter(rule)).map { it.id })
        // Absent artwork URI is UNKNOWN, and NOT must not promote it to present.
        assertEquals(emptyList<String>(), filterCanonicalBrowseAlbums(rows,
            filter(SmartPlaylistRule.Not(reference))).map { it.id })
        assertEquals(listOf("release:one", "release:two"),
            filterCanonicalBrowseAlbums(rows, filter(title)).map { it.id })
        assertFalse(filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.ALBUM_ARTIST,
            SmartPlaylistRuleOperator.IS_PRESENT,
        )).isSupportedForCanonicalAlbumBrowse())
        assertThrows(IllegalArgumentException::class.java) {
            filterCanonicalBrowseAlbums(rows, filter(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DURATION_MS,
                SmartPlaylistRuleOperator.IS_PRESENT,
            )))
        }
    }

    @Test fun songAlbumArtistScopesRejectReferenceAndUnverifiedMetadata() {
        val shared = filter(SmartPlaylistRule.Reference("p:1"))
        assertFalse(shared.isSupportedForCanonicalArtistBrowse())
        assertFalse(shared.isSupportedForCanonicalAlbumBrowse())
        val composer = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.TRACK_ARTIST,
            SmartPlaylistRuleOperator.IS_PRESENT,
        ))
        assertFalse(composer.isSupportedForCanonicalArtistBrowse())
        assertFalse(composer.isSupportedForCanonicalAlbumBrowse())
        assertTrue(filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_YEAR,
            SmartPlaylistRuleOperator.IS_PRESENT,
        )).isSupportedForCanonicalAlbumBrowse())
    }
    @Test fun blankCanonicalNamesAreUnknownNotProvenMissingForNegations() {
        val blankArtist = ArtistEntity("artist:unresolved", " ")
        val blankAlbum = ReleaseEntity("release:unresolved", "  ", null, null)
        val artistPresence = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.ARTIST_DISPLAY, SmartPlaylistRuleOperator.IS_PRESENT,
        )
        val artistMissing = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.ARTIST_DISPLAY, SmartPlaylistRuleOperator.IS_MISSING,
        )
        val albumPresence = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
        )
        val albumMissing = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_TITLE, SmartPlaylistRuleOperator.IS_MISSING,
        )
        // A placeholder name is unverified, not an affirmative IS_MISSING.
        for (rule in listOf(artistPresence, artistMissing,
            SmartPlaylistRule.Not(artistPresence), SmartPlaylistRule.Not(artistMissing))) {
            assertTrue(filterCanonicalBrowseArtists(listOf(blankArtist), filter(rule)).isEmpty())
        }
        for (rule in listOf(albumPresence, albumMissing,
            SmartPlaylistRule.Not(albumPresence), SmartPlaylistRule.Not(albumMissing))) {
            assertTrue(filterCanonicalBrowseAlbums(listOf(blankAlbum), filter(rule)).isEmpty())
        }
        val knownArtist = ArtistEntity("artist:known", "NewJeans")
        val knownAlbum = ReleaseEntity("release:known", "Get Up", null, null)
        assertEquals(listOf("artist:known"),
            filterCanonicalBrowseArtists(listOf(blankArtist, knownArtist),
                filter(artistPresence)).map { it.id })
        assertEquals(listOf("release:known"),
            filterCanonicalBrowseAlbums(listOf(blankAlbum, knownAlbum),
                filter(albumPresence)).map { it.id })
    }

    @Test fun sourceCategoryCanFilterAlbumsAndArtistsOnlyWhenProviderIsProven() {
        val album = ReleaseEntity("r:one", "Get Up", "NewJeans", null)
        val artist = ArtistEntity("a:one", "NewJeans")
        val local = com.music.bitchord.data.catalog.CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
        val localRule = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.SOURCE_KIND,
            SmartPlaylistRuleOperator.EQUAL, textValue = "local",
        ))
        val remoteRule = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.SOURCE_KIND,
            SmartPlaylistRuleOperator.EQUAL, textValue = "opensubsonic",
        ))
        val negatedRemote = filter(SmartPlaylistRule.Not(remoteRule.rules))
        assertTrue(localRule.isSupportedForCanonicalAlbumBrowse())
        assertTrue(localRule.isSupportedForCanonicalArtistBrowse())
        assertEquals(listOf("r:one"),
            filterCanonicalBrowseAlbums(listOf(album), localRule, local).map { it.id })
        assertEquals(listOf("a:one"),
            filterCanonicalBrowseArtists(listOf(artist), localRule, local).map { it.id })
        assertEquals(listOf("r:one"),
            filterCanonicalBrowseAlbums(listOf(album), remoteRule, "opensubsonic:one")
                .map { it.id })
        assertEquals(listOf("a:one"),
            filterCanonicalBrowseArtists(listOf(artist), remoteRule, "opensubsonic:one")
                .map { it.id })
        assertEquals(listOf("r:one"),
            filterCanonicalBrowseAlbums(listOf(album), negatedRemote, local).map { it.id })
        assertTrue(filterCanonicalBrowseAlbums(listOf(album), remoteRule, local).isEmpty())
        assertTrue(filterCanonicalBrowseArtists(listOf(artist), localRule,
            "opensubsonic:one").isEmpty())
        for (f in listOf(remoteRule, localRule, negatedRemote)) {
            // No account key, or an unsupported provider, must not satisfy even NOT.
            assertTrue(filterCanonicalBrowseAlbums(listOf(album), f).isEmpty())
            assertTrue(filterCanonicalBrowseArtists(listOf(artist), f).isEmpty())
            assertTrue(filterCanonicalBrowseAlbums(listOf(album), f,
                "custom:unknown").isEmpty())
            assertTrue(filterCanonicalBrowseArtists(listOf(artist), f,
                "custom:unknown").isEmpty())
        }
    }

    @Test fun releaseYearNeedsTrustedMusicBrainzEvidenceAndKeepsUnknownUnderNot() {
        val releases = listOf(
            ReleaseEntity("r:valid", "Get Up", "NewJeans", null),
            ReleaseEntity("r:unknown", "Get Up", "NewJeans", null),
            ReleaseEntity("r:invalid", "Get Up", "NewJeans", null),
        )
        val equal = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_YEAR, SmartPlaylistRuleOperator.EQUAL,
            value = 2023.0,
        ))
        val missing = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_YEAR, SmartPlaylistRuleOperator.IS_PRESENT,
        ))
        assertEquals(emptyList<String>(), filterCanonicalBrowseAlbums(
            releases, equal,
        ).map { it.id })
        assertEquals(listOf("r:valid"), filterCanonicalBrowseAlbums(
            releases, equal, releaseYears = mapOf("r:valid" to 2023),
        ).map { it.id })
        assertEquals(listOf("r:valid"), filterCanonicalBrowseAlbums(
            releases, missing, releaseYears = mapOf("r:valid" to 2023),
        ).map { it.id })
        assertTrue(filterCanonicalBrowseAlbums(releases, filter(
            SmartPlaylistRule.Not(missing.rules)),
            releaseYears = mapOf("r:valid" to 2023),
        ).isEmpty())
        val composite = filter(SmartPlaylistRule.Group(
            SmartPlaylistRuleJoin.ALL,
            listOf(SmartPlaylistRule.Comparison(SmartPlaylistRuleField.RELEASE_TITLE,
                SmartPlaylistRuleOperator.CONTAINS, textValue = "get"),
                equal.rules),
        ))
        assertEquals(listOf("r:valid"), filterCanonicalBrowseAlbums(
            releases, composite, releaseYears = mapOf("r:valid" to 2023),
        ).map { it.id })
    }

}
