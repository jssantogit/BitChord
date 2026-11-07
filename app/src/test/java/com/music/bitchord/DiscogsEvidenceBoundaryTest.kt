package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.CatalogTargetKind
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEntityKind
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsLinkLookup
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsParsing
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsReference
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsReferenceParser
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsRecordParser
import org.junit.Assert.*
import org.junit.Test

class DiscogsEvidenceBoundaryTest {
    private val releaseMbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val groupMbid = "529f87a1-1081-48a4-8ee1-3c08de229858"

    @Test fun typedDiscogsUrlsRejectConfusableOrUntrustedHosts() {
        val valid = DiscogsReferenceParser.fromUrl("http://www.discogs.com/release/12345-My-Album")
        assertEquals(DiscogsReference(DiscogsEntityKind.RELEASE, 12345), valid)
        assertEquals("https://www.discogs.com/release/12345", valid!!.sourceUrl)
        assertEquals(DiscogsReference(DiscogsEntityKind.MASTER, 6789),
            DiscogsReferenceParser.fromUrl("https://discogs.com/master/6789"))
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
        )) {
            assertNull("Untrusted Discogs URL must not be used: $unsafe",
                DiscogsReferenceParser.fromUrl(unsafe))
        }
    }

    @Test fun musicBrainzRelationshipRequiresExactMbidTypeUuidAndResourceKind() {
        val releaseJson = """{"id":"$releaseMbid","relations":[
            {"type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
             "target-type":"url","url":{"resource":"https://www.discogs.com/release/1212-EP"}},
            {"type":"discogs","type-id":"99e550f3-5ab4-3110-b5b9-fe01d970b126",
             "target-type":"url","url":{"resource":"https://www.discogs.com/master/2121"}},
            {"type":"other databases","target-type":"url",
             "url":{"resource":"https://www.discogs.com/release/9393"}}
        ]}"""
        assertEquals(DiscogsLinkLookup.Found(DiscogsReference(DiscogsEntityKind.RELEASE, 1212)),
            DiscogsReferenceParser.fromMusicBrainz(releaseJson, releaseMbid, CatalogTargetKind.RELEASE))
        assertEquals(DiscogsLinkLookup.Untrusted,
            DiscogsReferenceParser.fromMusicBrainz(releaseJson, groupMbid, CatalogTargetKind.RELEASE_GROUP))
        assertEquals(DiscogsLinkLookup.Missing,
            DiscogsReferenceParser.fromMusicBrainz(releaseJson.replace(releaseMbid, groupMbid),
                groupMbid, CatalogTargetKind.RELEASE_GROUP))
    }

    @Test fun distinctDiscogsLinksAreAmbiguousButDuplicatesAreNot() {
        val one = """{"type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
             "target-type":"url","url":{"resource":"https://www.discogs.com/release/111"}}"""
        val two = one.replace("/111", "/222")
        val duplicate = """{"id":"$releaseMbid","relations":[$one,$one]}"""
        val conflicting = """{"id":"$releaseMbid","relations":[$one,$two]}"""
        assertEquals(DiscogsLinkLookup.Found(DiscogsReference(DiscogsEntityKind.RELEASE, 111)),
            DiscogsReferenceParser.fromMusicBrainz(duplicate, releaseMbid, CatalogTargetKind.RELEASE))
        assertEquals(DiscogsLinkLookup.Ambiguous,
            DiscogsReferenceParser.fromMusicBrainz(conflicting, releaseMbid, CatalogTargetKind.RELEASE))
    }

    @Test fun releaseParserKeepsEditionScopedFactsAndNeverReturnsImagesAsData() {
        val raw = """{"id":456,"title":"ILLIT - SUPER REAL ME","year":2024,"country":"South Korea",
            "master_id":789,"artists":[{"id":11,"name":"ILLIT","anv":""}],
            "labels":[{"name":"Belift Lab","catno":"BEL-001"}],
            "identifiers":[{"type":"Barcode","value":"0 123456 789012"}],
            "formats":[{"name":"CD","qty":"1","descriptions":["EP","Album"]}],
            "tracklist":[{"position":"1","title":"My World","type_":"track"},
                         {"position":"2","title":"Magnetic","type_":"track"}],
            "images":[{"uri":"https://img.discogs.com/unlicensed.jpg"}]}"""
        val parsed = DiscogsRecordParser.release(raw, expectedId = 456) as
            DiscogsParsing.Success
        val record = parsed.record
        assertEquals(456L, record.reference.id)
        assertEquals(DiscogsEntityKind.RELEASE, record.reference.kind)
        assertEquals(789L, record.masterId)
        assertEquals("South Korea", record.country)
        assertEquals("Belift Lab", record.labels.single().name)
        assertEquals("BEL-001", record.labels.single().catalogNumber)
        assertEquals("0 123456 789012", record.barcodes.single())
        assertEquals(listOf("My World", "Magnetic"), record.tracks.map { it.title })
        assertFalse(record.unofficial)
        assertFalse(record.toString().contains("unlicensed.jpg"))
    }

    @Test fun explicitUnofficialEditionRemainsEditionScopedAndDoesNotRewriteMaster() {
        val raw = """{"id":10,"title":"Remix Collection","year":1998,
            "formats":[{"name":"Vinyl","descriptions":["12\"","Unofficial Release"]}],
            "master_id":30,"tracklist":[{"position":"A1","title":"Remix"}]}"""
        val release = (DiscogsRecordParser.release(raw, 10) as DiscogsParsing.Success).record
        assertTrue(release.unofficial)
        assertEquals("A1", release.tracks.single().position)
        val master = (DiscogsRecordParser.master(
            """{"id":30,"title":"Original Album","year":1997,"main_release":11}""", 30)
            as DiscogsParsing.Success).record
        assertEquals(DiscogsEntityKind.MASTER, master.reference.kind)
        assertEquals(11L, master.mainReleaseId)
        assertFalse(master is com.music.bitchord.data.catalog.metadata.discogs.DiscogsReleaseRecord)
    }

    @Test fun parserFailsClosedForWrongIdBadPayloadOrOverflowAndPreservesCjk() {
        assertEquals(DiscogsParsing.WrongId,
            DiscogsRecordParser.release("""{"id":18,"title":"A"}""", expectedId = 17))
        assertEquals(DiscogsParsing.Invalid,
            DiscogsRecordParser.release("""{"id":"bad","title":"A"}""", expectedId = 17))
        assertEquals(DiscogsParsing.Invalid, DiscogsRecordParser.release("{no}", 17))
        assertEquals(DiscogsParsing.Invalid, DiscogsRecordParser.release("x".repeat(300_000), 17))
        val cjk = (DiscogsRecordParser.release(
            """{"id":21,"title":"愛してる (사랑)","artists":[{"name":"宇多田ヒカル"}]}""", 21)
            as DiscogsParsing.Success).record
        assertEquals("愛してる (사랑)", cjk.title)
        assertEquals("宇多田ヒカル", cjk.artistNames.single())
    }

    @Test fun identityMustNotSupplyPlaybackOrAssumeCoverPermission() {
        val r = DiscogsReference(DiscogsEntityKind.RELEASE, 99)
        assertFalse(r.canClaimPlayableAudio)
        assertFalse(r.canSupplyArtwork)
    }
}
