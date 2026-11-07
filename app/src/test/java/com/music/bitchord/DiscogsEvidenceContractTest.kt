package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.discogs.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsEvidenceContractTest {
    private val releaseMbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val groupMbid = "529f87a1-1081-48a4-8ee1-3c08de229858"

    @Test fun onlyAnExactDiscogsWebReleaseOrMasterLinkIsAccepted() {
        assertEquals(DiscogsLink(DiscogsKind.RELEASE, 12345L),
            DiscogsLinkParser.parse("https://www.discogs.com/release/12345-Example-Album"))
        assertEquals(DiscogsLink(DiscogsKind.MASTER, 42L),
            DiscogsLinkParser.parse("https://discogs.com/master/42"))
        for (unsafe in listOf(
            "http://www.discogs.com/release/12345", "https://discogs.com.evil.test/release/12345",
            "https://user@discogs.com/release/12345", "https://discogs.com:443/release/12345",
            "https://discogs.com/release/12345?key=secret", "https://discogs.com/release/0",
            "https://discogs.com/release/12345/versions", "https://discogs.com/master/abc",
            "https://api.discogs.com/releases/12345", "file:///release/12345",
        )) assertNull(unsafe, DiscogsLinkParser.parse(unsafe))
        assertEquals("https://www.discogs.com/release/12345",
            DiscogsLink(DiscogsKind.RELEASE, 12345).attributionUrl)
    }

    @Test fun musicBrainzReleaseRelationIsAnAnchorNotAClaimOfEditionEquivalence() = runBlocking {
        val repository = DiscogsMusicBrainzLinks(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>,
                    userAgent: String): String {
                    assertEquals("release/$releaseMbid", path)
                    assertEquals("url-rels", parameters["inc"])
                    return """
                       {"id":"$releaseMbid","relations":[
                         {"type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                          "url":{"resource":"https://www.discogs.com/release/12345-Example"}},
                         {"type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                          "ended":true,"url":{"resource":"https://www.discogs.com/release/77777"}},
                         {"type":"other databases","url":{"resource":"https://www.discogs.com/release/99999"}},
                         {"type":"discogs","url":{"resource":"https://evil.example/release/2024"}}
                       ]}
                    """.trimIndent()
                }
            },
        ))
        val result = repository.findRelease(releaseMbid)
        assertEquals(DiscogsRelationResult.Linked(DiscogsLink(DiscogsKind.RELEASE, 12345)),
            result)
    }

    @Test fun aMasterIsNotAutomaticallyAReleaseEditionAndConflictingLinksAreAmbiguous() = runBlocking {
        val client = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String =
                """{"id":"$groupMbid","relations":[
                    {"type":"discogs","type-id":"99e550f3-5ab4-3110-b5b9-fe01d970b126",
                     "url":{"resource":"https://www.discogs.com/master/55"}},
                    {"type":"discogs","type-id":"99e550f3-5ab4-3110-b5b9-fe01d970b126",
                     "url":{"resource":"https://www.discogs.com/master/56"}}
                 ]}"""
        })
        assertEquals(DiscogsRelationResult.Ambiguous,
            DiscogsMusicBrainzLinks(client).findReleaseGroup(groupMbid))
    }

    @Test fun invalidOrContradictoryMusicBrainzRelationsFailClosed() = runBlocking {
        val client = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String =
                """{"id":"00000000-0000-0000-0000-000000000000",
                    "relations":[{"type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                    "url":{"resource":"https://www.discogs.com/release/66"}}]}"""
        })
        assertEquals(DiscogsRelationResult.InvalidResponse,
            DiscogsMusicBrainzLinks(client).findRelease(releaseMbid))
        assertEquals(DiscogsRelationResult.InvalidCanonicalId,
            DiscogsMusicBrainzLinks(client).findRelease("same-title"))
    }

    @Test fun parserKeepsEditionMetadataButNeverEmitsRestrictedArtworkOrMediaAsset() {
        val raw = """
            {"id":12345,"title":"예쁜 앨범","country":"KR","year":2024,
             "artists":[{"name":"아티스트"}],
             "labels":[{"name":"Label A","catno":"A-001"}],
             "identifiers":[{"type":"Barcode","value":"0 12345 67890 5"}],
             "formats":[{"name":"CD","descriptions":["Album","Unofficial Release"]}],
             "tracklist":[{"position":"A1","title":"트랙 一","duration":"3:10"}],
             "images":[{"uri":"https://images.discogs.com/not-licensed.jpg"}],
             "community":{"have":100000}}
        """.trimIndent()
        val parsed = DiscogsRecordParser.parse(DiscogsLink(DiscogsKind.RELEASE,12345), raw)
        assertNotNull(parsed)
        assertEquals("예쁜 앨범", parsed!!.title)
        assertEquals(listOf("아티스트"), parsed.artistNames)
        assertEquals(listOf("0 12345 67890 5"), parsed.barcodes)
        assertEquals(listOf("Label A" to "A-001"), parsed.labels)
        assertEquals(listOf("A1" to "트랙 一"), parsed.tracklist)
        assertTrue(parsed.unofficial)
        assertEquals("https://www.discogs.com/release/12345", parsed.attributionUrl)
        assertFalse(parsed.toString().contains("images.discogs.com"))
        assertNull(DiscogsRecordParser.parse(DiscogsLink(DiscogsKind.RELEASE, 12346),raw))
        assertNull(DiscogsRecordParser.parse(DiscogsLink(DiscogsKind.MASTER,12345),raw))
    }

    @Test fun oversizedAndMalformedInputsCannotCreateEditionFacts() {
        val link = DiscogsLink(DiscogsKind.RELEASE, 3)
        assertNull(DiscogsRecordParser.parse(link, "{}"))
        assertNull(DiscogsRecordParser.parse(link, "{broken"))
        assertNull(DiscogsRecordParser.parse(link, """{"id":3,"title":"X","tracklist":[1,2]}"""))
        assertNull(DiscogsRecordParser.parse(link, """{"id":3,"title":"X","images":[1]}""".repeat(50_000)))
    }

    @Test fun evidenceExpiresAndCannotSupplyPlayOrArtwork() {
        val observation = DiscogsObservation(
            canonicalMbid = releaseMbid,
            kind = DiscogsKind.RELEASE,
            record = DiscogsRecord(
                link = DiscogsLink(DiscogsKind.RELEASE, 3), title = "Example",
                artistNames = listOf("Artist"), country = null, year = 2020,
                labels = emptyList(), barcodes = emptyList(), formats = emptyList(),
                tracklist = emptyList(), unofficial = false,
            ),
            fetchedAtMillis = 1_000L,
        )
        assertTrue(observation.isDisplayableAt(1_000L))
        assertTrue(observation.isDisplayableAt(1_000L + DiscogsObservation.TTL_MILLIS - 1L))
        assertFalse(observation.isDisplayableAt(1_000L + DiscogsObservation.TTL_MILLIS))
        assertFalse(observation.isDisplayableAt(999L))
        assertFalse(observation.canSupplyAudio())
        assertFalse(observation.canSupplyArtwork())
    }
}
