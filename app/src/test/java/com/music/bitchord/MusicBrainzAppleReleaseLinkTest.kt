package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.musicBrainzAppleReleaseAlbumIds
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzAppleReleaseLinkTest {
    @Test fun onlyExplicitReleaseLevelAppleAlbumRelationsCountAsProductLinks() {
        val relations = Json.parseToJsonElement("""[
            {"type":"purchase for download","target-type":"url",
             "url":{"resource":"https://itunes.apple.com/us/album/super-real-me/id101"}},
            {"type":"streaming","target-type":"url",
             "url":{"resource":"https://music.apple.com/br/album/super-real-me/101"}},
            {"type":"streaming","target-type":"url",
             "url":{"resource":"https://music.apple.com/us/album/variant/102"}},
            {"type":"official homepage","target-type":"url",
             "url":{"resource":"https://music.apple.com/br/album/another/103"}},
            {"type":"streaming","target-type":"artist",
             "url":{"resource":"https://music.apple.com/br/album/another/104"}}
        ]""")
        assertEquals(listOf(101L, 102L), musicBrainzAppleReleaseAlbumIds(relations))
    }

    @Test fun forgedOrTrackOrRedirectedAppleUrlsAreNeverAlbumProof() {
        val relations = Json.parseToJsonElement("""[
            {"type":"purchase for download","target-type":"url",
             "url":{"resource":"https://music.apple.com.evil.example/br/album/test/101"}},
            {"type":"streaming","target-type":"url",
             "url":{"resource":"http://music.apple.com/br/album/test/102"}},
            {"type":"streaming","target-type":"url",
             "url":{"resource":"https://music.apple.com/br/album/test/103?i=201"}},
            {"type":"streaming","target-type":"url",
             "url":{"resource":"https://music.apple.com/br/artist/test/104"}},
            {"type":"streaming","target-type":"url",
             "url":{"resource":"https://user@music.apple.com/br/album/test/105"}},
            {"type":"streaming","target-type":"url",
             "url":{"resource":"https://music.apple.com/br/album/test/notanid"}},
            {"type":"streaming","target-type":"url",
             "url":{"resource":"https://music.apple.com/br/album/test/0"}}
        ]""")
        assertTrue(musicBrainzAppleReleaseAlbumIds(relations).isEmpty())
    }

    @Test fun nonArrayRelationsAreNeverTrusted() {
        assertTrue(musicBrainzAppleReleaseAlbumIds(null).isEmpty())
        assertTrue(musicBrainzAppleReleaseAlbumIds(Json.parseToJsonElement("{}")).isEmpty())
    }
}
