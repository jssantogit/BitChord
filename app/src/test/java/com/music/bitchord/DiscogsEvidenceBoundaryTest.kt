package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.discogs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Adversarial safety cases supplementing the initial Discogs contract tests. */
class DiscogsEvidenceBoundaryTest {
    private val releaseMbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val groupMbid = "529f87a1-1081-48a4-8ee1-3c08de229858"

    @Test fun legacyMusicBrainzHttpResourceIsNormalizedToSafeHttpsAttribution() {
        // The release link in MusicBrainz may be historical HTTP. No HTTP
        // request ever follows it; only the typed numeric Discogs ID is used.
        val parsed = DiscogsLinkParser.parse("http://www.discogs.com/release/12345")
        assertEquals(DiscogsLink(DiscogsKind.RELEASE, 12345L), parsed)
        assertEquals("https://www.discogs.com/release/12345", parsed!!.attributionUrl)
        assertNull(DiscogsLinkParser.parse("http://discogs.com.evil.org/release/12345"))
        assertNull(DiscogsLinkParser.parse("http://discogs.com@evil.org/release/12345"))
    }

    @Test fun untrustedUrlShapesCannotBecomeDiscogsApiRequests() {
        for (unsafe in listOf(
            "https://discogs.com.evil.org/release/12345",
            "https://evil.org/?next=discogs.com/release/12345",
            "https://discoGs.com@evil.org/release/12345",
            "https://discogs.com:8443/release/12345",
            "file:///release/12345",
            "https://discogs.com/release/-1",
            "https://discogs.com/release/0",
            "https://discogs.com/release/999999999999999999999999",
            "https://discogs.com/release/12345?token=private",
            "https://discogs.com/release/12345/extra",
            "https://discogs.com/artist/12345",
            "https://discogs.com/release/%31%32%33",
        )) assertNull("Untrusted URL: $unsafe", DiscogsLinkParser.parse(unsafe))
    }

    @Test fun conflictingReleaseRelationsAreAmbiguousInsteadOfTakingFirst() = runBlocking {
        val links = DiscogsMusicBrainzLinks(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>,
                    userAgent: String): String =
                    """{"id":"$releaseMbid","relations":[
                      {"type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                       "url":{"resource":"https://www.discogs.com/release/111"}},
                      {"type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                       "url":{"resource":"https://www.discogs.com/release/222"}}
                    ]}"""
            }
        ))
        assertEquals(DiscogsRelationResult.Ambiguous, links.findRelease(releaseMbid))
    }

    @Test fun wrongResourceKindRejectsContradictoryMusicBrainzRelation() = runBlocking {
        val links = DiscogsMusicBrainzLinks(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>,
                    userAgent: String): String =
                    """{"id":"$groupMbid","relations":[
                      {"type":"discogs","type-id":"99e550f3-5ab4-3110-b5b9-fe01d970b126",
                       "url":{"resource":"https://www.discogs.com/release/333"}}
                    ]}"""
            }
        ))
        assertEquals(DiscogsRelationResult.InvalidResponse, links.findReleaseGroup(groupMbid))
    }

    @Test fun repeatedValidDiscogsLinksRemainOneCandidate() = runBlocking {
        val links = DiscogsMusicBrainzLinks(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>,
                    userAgent: String): String =
                    """{"id":"$releaseMbid","relations":[
                      {"type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                       "url":{"resource":"https://discogs.com/release/111"}},
                      {"type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                       "url":{"resource":"https://www.discogs.com/release/111-Same-Edition"}}
                    ]}"""
            }
        ))
        assertEquals(
            DiscogsRelationResult.Linked(DiscogsLink(DiscogsKind.RELEASE, 111L)),
            links.findRelease(releaseMbid),
        )
    }

    @Test fun releaseEditionDataAndCjkSurviveWhileImagesAndMarketplaceAreDropped() {
        val raw = """{"id":456,"title":"ILLIT - 愛してる (사랑)","year":2024,
            "country":"South Korea","artists":[{"id":11,"name":"宇多田ヒカル"}],
            "labels":[{"name":"Belift Lab","catno":"BEL-001"}],
            "identifiers":[{"type":"Barcode","value":"0 123456 789012"}],
            "formats":[{"name":"CD","qty":"1","descriptions":["EP","Album"]}],
            "tracklist":[{"position":"1","title":"My World","type_":"track"},
                         {"position":"2","title":"Magnetic","type_":"track"}],
            "images":[{"uri":"https://img.discogs.com/unlicensed.jpg"}],
            "marketplace":{"price":99}}"""
        val r = DiscogsRecordParser.parse(DiscogsLink(DiscogsKind.RELEASE, 456), raw)
        assertNotNull(r)
        assertEquals("ILLIT - 愛してる (사랑)", r!!.title)
        assertEquals("宇多田ヒカル", r.artistNames.single())
        assertEquals("Belift Lab" to "BEL-001", r.labels.single())
        assertEquals("0 123456 789012", r.barcodes.single())
        assertEquals(listOf("My World", "Magnetic"), r.tracklist.map { it.second })
        assertFalse(r.toString().contains("unlicensed.jpg"))
        assertFalse(r.toString().contains("marketplace"))
    }

    @Test fun malformedOrExcessivelyLargeTracklistsDoNotCreateFacts() {
        val bad = """{"id":10,"title":"Large","tracklist":[""" +
            List(260) { """{"position":"A1","title":"Song"}""" }.joinToString(",") + "]}"
        assertNull(DiscogsRecordParser.parse(DiscogsLink(DiscogsKind.RELEASE,10),bad))
        assertNull(DiscogsRecordParser.parse(DiscogsLink(DiscogsKind.RELEASE,10),
            """{"id":"not-a-number","title":"A"}"""))
        assertNull(DiscogsRecordParser.parse(DiscogsLink(DiscogsKind.RELEASE,10),
            "x".repeat(300_000)))
    }

    @Test fun explicitUnofficialEditionDoesNotBecomeAPlaySourceOrCover() {
        val record = DiscogsRecordParser.parse(DiscogsLink(DiscogsKind.RELEASE,10),
            """{"id":10,"title":"Remix Collection","year":1998,
                "formats":[{"name":"Vinyl","descriptions":["Unofficial Release"]}],
                "tracklist":[{"position":"A1","title":"Remix"}]}""")!!
        assertTrue(record.unofficial)
        assertEquals("A1" to "Remix", record.tracklist.single())
        val o = DiscogsObservation(releaseMbid, DiscogsKind.RELEASE, record, 1_000L)
        assertFalse(o.canSupplyAudio())
        assertFalse(o.canSupplyArtwork())
        assertFalse(o.isDisplayableAt(999L))
    }
}
