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

    @Test fun userQueryReallyReplacesPreviouslyHardcodedSearchOnWire() {
        val one = A0OneShotPublicSearchWire.fileSearch(42, "newjeans - omg")
        val two = A0OneShotPublicSearchWire.fileSearch(42, "naruto*")
        assertEquals("newjeans omg", searchTerm(one))
        assertEquals("naruto", searchTerm(two))
        assertFalse(one.contentEquals(two))
        assertFalse(one.toString(Charsets.UTF_8).contains("naruto"))
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
