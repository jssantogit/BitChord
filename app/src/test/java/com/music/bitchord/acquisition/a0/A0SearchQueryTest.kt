package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/** User typed query is read-only, bounded, punctuation normalized, never logged. */
class A0SearchQueryTest {
    @Test fun artistSongQueryAndPunctuationProduceSafeSoulseekTerm() {
        assertEquals("newjeans omg", A0SearchQuery.normalize("  newjeans - omg  "))
        assertEquals("naruto", A0SearchQuery.normalize("naruto*"))
        assertEquals("Björk Jóga", A0SearchQuery.normalize(" Björk - Jóga "))
        assertEquals("Daft Punk", A0SearchQuery.normalize("Daft   Punk"))
    }

    @Test fun latinAccentFallbackNeverDecomposesKoreanIntoJamo() {
        assertEquals(listOf("BTS 노래 Live","노래 Live"),
            A0SearchQuery.variants("BTS - 노래 (Live)"))
        assertEquals(listOf("Björk Jóga","Bjork Joga","Jóga"),
            A0SearchQuery.variants("Björk - Jóga"))
        val multilingual=A0SearchQuery.variants("Björk - 상남자 (Live)")
        assertEquals("Bjork 상남자 Live",multilingual[1])
        assertEquals("상남자 Live",multilingual[2])
        assertFalse(multilingual.any { it.contains("ᄉ") || it.contains("ᅡ") })
    }

    @Test fun canonicallyDecomposedUnicodeNeverSplitsArtistOrKoreanSyllables() {
        val decomposed="Bjo\u0308rk - Jo\u0301ga"
        assertEquals("Björk Jóga",A0SearchQuery.normalize(decomposed))
        assertEquals(listOf("Björk Jóga","Bjork Joga","Jóga"),
            A0SearchQuery.variants(decomposed))
        assertEquals("BTS 노래",A0SearchQuery.normalize(
            "BTS - \u1102\u1169\u1105\u1162"))
        // A two-syllable title remains below the conservative
        // three-character minimum for a broad title-only search.
        assertNull(A0SearchQuery.titleOnlyFallback(
            "BTS - \u1102\u1169\u1105\u1162"))
        // The same canonical-composition path still permits a sufficiently
        // specific Korean title without decomposing its Hangul syllables.
        assertEquals("사랑해",A0SearchQuery.titleOnlyFallback(
            "BTS - \u1109\u1161\u1105\u1161\u11bc\u1112\u1162"))
    }

    @Test fun combiningMarksWithinRealWordsAreRetainedButMarkOnlyQueryIsRejected() {
        // Devanagari vowel signs do not always have NFC precomposed letters.
        assertEquals("भारत गीत",A0SearchQuery.normalize("भारत - गीत"))
        assertEquals("गीत",A0SearchQuery.titleOnlyFallback("भारत - गीत"))
        assertEquals("ภาษาไทย",A0SearchQuery.normalize("ภาษาไทย"))
        try {
            A0SearchQuery.normalize("\u093e\u0301")
            fail("Combining-mark-only search must stay invalid")
        } catch (_:IllegalArgumentException) { }
    }

    @Test fun userQueryReallyReplacesPreviouslyHardcodedSearchOnWire() {
        val one = A0OneShotPublicSearchWire.fileSearch(42, "newjeans - omg")
        val two = A0OneShotPublicSearchWire.fileSearch(42, "naruto*")
        assertEquals("newjeans omg", searchTerm(one))
        assertEquals("naruto", searchTerm(two))
        assertFalse(one.contentEquals(two))
        assertFalse(one.toString(Charsets.UTF_8).contains("naruto"))
    }

    @Test fun automaticSearchRejectsArtistOnlyAndMalformedCanonicalTitles() {
        assertEquals("BTS 노래",
            A0SearchQuery.canonicalArtistTitle("BTS - 노래"))
        assertEquals("ILLIT Magnetic",
            A0SearchQuery.canonicalArtistTitle("ILLIT - Magnetic"))
        for (bad in listOf("BTS - ---","BTS - 🎵","--- - Magnetic",
            "BTS Magnetic","BTS -   ","BTS - " + "x".repeat(120))) {
            assertNull("Invalid artist/title may not become a public search",
                A0SearchQuery.canonicalArtistTitle(bad))
        }
        // A user explicitly entering an unstructured manual query remains valid.
        assertEquals("BTS Magnetic",A0SearchQuery.normalize("BTS Magnetic"))
    }

    @Test fun boundedFallbackUsesTrackTitleToAvoidOverconstrainedArtistQueries() {
        assertEquals("Billie Jean", A0SearchQuery.titleOnlyFallback(
            "Michael Jackson - Billie Jean"))
        assertEquals("Dynamite", A0SearchQuery.titleOnlyFallback(
            "BTS (방탄소년단) - Dynamite"))
        assertEquals("Magnetic", A0SearchQuery.titleOnlyFallback(
            "ILLIT - Magnetic"))
        assertNull(A0SearchQuery.titleOnlyFallback("Michael Jackson Billie Jean"))
        assertNull(A0SearchQuery.titleOnlyFallback("artist - x"))
        assertNull(A0SearchQuery.titleOnlyFallback("artist - ---"))
    }

    @Test fun editionSearchVariationsPreserveAllCanonicalSongTerms() {
        assertEquals("BTS Epiphany Jin Demo Version",
            A0SearchQuery.editionSpellingVariant(
                "BTS - Epiphany (Jin Demo Ver.)"))
        assertEquals("BTS Still With You Acapella",
            A0SearchQuery.editionSpellingVariant(
                "BTS - Still With You (A Cappella)"))
        assertEquals(listOf("BTS Epiphany Jin Demo Ver",
            "BTS Epiphany Jin Demo Version","Epiphany Jin Demo Ver"),
            A0SearchQuery.variants("BTS - Epiphany (Jin Demo Ver.)"))
        assertNull(A0SearchQuery.editionSpellingVariant("BTS - Quiero Ver"))
        assertNull(A0SearchQuery.editionSpellingVariant("BTS - Epiphany"))
    }

    @Test fun emptyPunctuationControlCharactersAndOversizedQueriesFailClosed() {
        for (bad in listOf("", "   ", "---", "***", "../", "x".repeat(121),
            "some\nsearch", "bad\u0000name", "é".repeat(100))) {
            try {
                A0SearchQuery.normalize(bad)
                fail("Invalid query must be rejected before login or search")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun noQueryIsUsedToAuthorizeDownload() {
        assertEquals("Ringingsound.wav", A0OneShotPublicSearchWire.TARGET_FILENAME)
        val r = A0CommonsOriginalReference.reviewedOriginal()
        assertEquals("Ringingsound.wav", r.expectedFilename)
        assertNotEquals("newjeans omg", r.expectedFilename)
    }

    private fun searchTerm(packet: ByteArray): String {
        val stream = ByteArrayInputStream(packet)
        fun int(): Int {
            val b = ByteArray(4)
            if (stream.read(b) != 4) throw IllegalStateException("truncated")
            return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
                ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
        }
        assertEquals(packet.size - 4, int())
        assertEquals(26, int())
        assertEquals(42, int())
        val length = int()
        assertTrue(length in 1..160)
        val b = ByteArray(length)
        assertEquals(length, stream.read(b))
        assertEquals(-1, stream.read())
        return String(b, Charsets.UTF_8)
    }
}
