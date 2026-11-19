package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCatalog
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebArtistShelf
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebProtocolException
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTransport
import com.music.bitchord.data.providers.ProviderCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebDiscographyPagingTest {
    @Test fun artistDiscographyUsesAppleNextOffsetNotArithmetic() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val source = AppleMusicWebTransport { _, path, args ->
            requests += path to args
            if ("offset" in args) {
                """{"data":[{"id":"33","type":"albums","attributes":{"name":"Final album"}}]}"""
            } else {
                """{"data":[{"id":"31","type":"albums","attributes":{"name":"First album"}}],
                   "next":"/v1/catalog/br/artists/42/albums?offset=7&limit=1"}"""
            }
        }
        val page = AppleMusicWebCatalog(source).artistCollection(
            "42", "br", AppleMusicWebArtistShelf.ALL_ALBUMS, page = 2, limit = 1,
        )
        assertEquals(listOf("33"), page.items.map { it.id })
        assertEquals("7", requests.last().second["offset"])
        assertEquals("/v1/catalog/br/artists/42/albums", requests.first().first)
        assertEquals("apple_music_web:br:album:33", page.items.single().scopedId)
    }

    @Test fun albumTracksFollowOnlyItsOwnRelationshipCursor() = runBlocking {
        val requests = mutableListOf<String>()
        val transport = AppleMusicWebTransport { capability, path, args ->
            assertEquals(ProviderCapability.EDITORIAL_DISCOGRAPHY, capability)
            requests += path
            if (path.endsWith("/relationships/tracks")) {
                assertEquals("9", args["offset"])
                """{"data":[{"id":"5","type":"songs","attributes":{"name":"Second track"}}]}"""
            } else {
                """{"data":[{"id":"10","type":"albums","attributes":{"name":"Album"},
                "relationships":{"tracks":{"data":[
                     {"id":"4","type":"songs","attributes":{"name":"First track"}}
                ],"next":"/v1/catalog/br/albums/10/relationships/tracks?offset=9"}}}]}"""
            }
        }
        val album = AppleMusicWebCatalog(transport).album("10", "br")
        assertEquals(listOf("4", "5"), album.tracks.map { it.id })
        assertEquals(2, requests.size)
    }

    @Test fun albumRejectsCrossAlbumTrackCursor() = runBlocking {
        val transport = AppleMusicWebTransport { _, _, _ ->
            """{"data":[{"id":"10","type":"albums","attributes":{"name":"Album"},
              "relationships":{"tracks":{"data":[],
              "next":"/v1/catalog/br/albums/11/relationships/tracks?offset=9"}}}]}"""
        }
        try {
            AppleMusicWebCatalog(transport).album("10", "br")
            fail("Cannot follow a relationship for a different album")
        } catch (_: AppleMusicWebProtocolException) { }
    }

    @Test fun unsupportedArtistShelfCannotUseHiddenFallback() = runBlocking {
        val transport = AppleMusicWebTransport { _, _, _ -> fail("No transport expected"); "{}" }
        try {
            AppleMusicWebCatalog(transport).artistCollection(
                "42", "br", AppleMusicWebArtistShelf.ALL_ALBUMS, page = 0, limit = 50,
            )
            fail("Invalid page")
        } catch (_: IllegalArgumentException) { }
    }
}
