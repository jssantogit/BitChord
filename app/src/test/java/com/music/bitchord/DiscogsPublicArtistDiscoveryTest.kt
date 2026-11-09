package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import org.junit.Assert.*
import org.junit.Test

class DiscogsPublicArtistDiscoveryTest {
    private val mbid = "95663592-1447-4579-90f7-c94733476667"

    private fun relation(id: Long = 8303009L): String =
        """{"id":"$mbid","relations":[{
            "type":"discogs",
            "url":{"resource":"https://www.discogs.com/artist/$id-ENHYPEN"}
        }]}"""

    @Test fun onlyExactMusicBrainzArtistRelationCanAnchorAnonymousListing() {
        assertEquals(8303009L,
            DiscogsPublicArtistParsing.artistId(mbid, relation()))
        assertNull(DiscogsPublicArtistParsing.artistId(
            "00000000-0000-4000-8000-000000000001", relation()))
        assertNull(DiscogsPublicArtistParsing.artistId(mbid,
            relation().replace("www.discogs.com", "evil.example")))
        assertNull(DiscogsPublicArtistParsing.artistId(mbid,
            relation().replace("/artist/8303009", "/release/8303009")))
        assertNull(DiscogsPublicArtistParsing.artistId(mbid,
            """{"id":"$mbid","relations":[
                {"type":"discogs","url":{"resource":"https://discogs.com/artist/11"}},
                {"type":"discogs","url":{"resource":"https://discogs.com/artist/12"}}
            ]}"""))
    }

    @Test fun pageUsesMainRoleAndTypedMasterReleaseIdsWithStrictPagination() {
        val page = DiscogsPublicArtistParsing.page("""
            {"pagination":{"page":1,"pages":3},"releases":[
              {"id":10,"type":"master","role":"Main","title":"Album"},
              {"id":11,"type":"release","role":"Main","title":"Single"},
              {"id":12,"type":"release","role":"Producer","title":"Credit"},
              {"id":13,"type":"master","role":"Appearance"},
              {"id":10,"type":"master","role":"Main"}
            ]}""", 1)
        assertNotNull(page)
        assertEquals(listOf(
            DiscogsArtistIndexEntry(DiscogsKind.MASTER, 10, "Album"),
            DiscogsArtistIndexEntry(DiscogsKind.RELEASE, 11, "Single"),
        ), page?.entries)
        assertEquals(3, page?.totalPages)
        assertNull(DiscogsPublicArtistParsing.page(
            """{"pagination":{"page":9,"pages":2},"releases":[]}""", 1))
        assertNull(DiscogsPublicArtistParsing.page(
            """{"pagination":{"page":1,"pages":3},"releases":null}""", 1))
    }

    @Test fun withoutExactArtistLinkNoCatalogDiscoveryShouldBeAuthorized() {
        assertNull(DiscogsPublicArtistParsing.artistId(mbid,
            """{"id":"$mbid","relations":[]}"""))
        assertNull(DiscogsPublicArtistParsing.artistId("local:artist", relation()))
        assertNull(DiscogsPublicArtistParsing.artistId(mbid,
            """{"id":"$mbid","relations":[{
                "type":"discogs","ended":true,
                "url":{"resource":"https://discogs.com/artist/8303009"}
            }]}"""))
    }
}
