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
        assertFalse(filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_YEAR,
            SmartPlaylistRuleOperator.IS_PRESENT,
        )).isSupportedForCanonicalAlbumBrowse())
    }
}
