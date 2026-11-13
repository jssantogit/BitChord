package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import org.junit.Assert.*
import org.junit.Test

/** Regressions from Discogs' API conventions, not just simplified JSON fixtures. */
class DiscogsMatchingAdversarialTest {
    private val mbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val link = DiscogsLink(DiscogsKind.RELEASE, 123L)

    @Test fun artistPrefixedDiscogsTitleMustNotRejectAnOtherwiseIdenticalEdition() {
        // Discogs API "title" commonly uses "Artist - Album", unlike MusicBrainz
        // Release.title which contains the album title without its artist prefix.
        val musicBrainz = CanonicalEditionEvidence(
            musicBrainzReleaseId = mbid, title = "SUPER REAL ME",
            creditedArtist = "ILLIT", status = "Official",
            barcodes = listOf("036000291452"),
            trackTitles = listOf("My World", "Magnetic"),
        )
        val discogs = DiscogsRecord(
            link = link, title = "ILLIT - SUPER REAL ME", artistNames = listOf("ILLIT"),
            country = "KR", year = 2024,
            labels = listOf("Belift Lab" to "001"),
            barcodes = listOf("0 36000 29145 2"),
            formats = listOf("CD", "EP"),
            tracklist = listOf("1" to "My World", "2" to "Magnetic"),
            unofficial = false,
        )
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assess(musicBrainz, link, discogs))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assess(musicBrainz, link,
                discogs.copy(title = "ILLIT - DIFFERENT ALBUM")))
    }

    @Test fun headingRowsAreNotDiscogsAudioTracks() {
        val parsed = DiscogsRecordParser.parse(link,
            """{"id":123,"title":"ILLIT - SUPER REAL ME",
               "tracklist":[
                 {"type_":"heading","title":"Disc One"},
                 {"type_":"track","position":"1","title":"My World"},
                 {"type_":"index","title":"Suite","position":""},
                 {"type_":"track","position":"2","title":"Magnetic"}
               ]}""")
        assertNotNull(parsed)
        assertEquals(listOf("1" to "My World", "2" to "Magnetic"), parsed!!.tracklist)
    }
}
