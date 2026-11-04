package com.music.bitchord.data.catalog

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOggTagParserTest {
    private fun le32(value: Int) = byteArrayOf(
        value.toByte(), (value shr 8).toByte(),
        (value shr 16).toByte(), (value shr 24).toByte(),
    )
    private fun comments(vararg tags: String): ByteArray {
        val vendor = "Bitchord test".toByteArray(Charsets.UTF_8)
        val pairs = tags.map { it.toByteArray(Charsets.UTF_8) }
        return le32(vendor.size) + vendor + le32(pairs.size) +
            pairs.fold(ByteArray(0)) { total, bytes ->
                total + le32(bytes.size) + bytes
            }
    }
    private fun page(serial: Int, sequence: Int, packet: ByteArray, first: Boolean, continued: Boolean = false): ByteArray {
        val segments = mutableListOf<Int>()
        var left = packet.size
        while (left >= 255) { segments += 255; left -= 255 }
        segments += left
        val header = byteArrayOf(
            'O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(),
            0, (if (first) 2 else if (continued) 1 else 0).toByte(),
        ) + ByteArray(8) + le32(serial) + le32(sequence) +
            ByteArray(4) + byteArrayOf(segments.size.toByte()) +
            segments.map(Int::toByte).toByteArray()
        return header + packet
    }

    @Test fun opusTagsWithRepeatedCommentsUsesSameCanonicalFields() {
        val head = "OpusHead".toByteArray(Charsets.US_ASCII) + ByteArray(11)
        val tags = "OpusTags".toByteArray(Charsets.US_ASCII) + comments(
            "GENRE=Trip Hop", "GENRE=Downtempo", "MOOD=Calm",
            "STYLE=Ambient", "BPM=92.5",
        )
        val payload = page(42, 0, head, first = true) +
            page(42, 1, tags, first = false) + ByteArray(80_000)
        val stream = ByteArrayInputStream(payload)
        val parsed = requireNotNull(LocalEmbeddedTagParser.readStream(stream))
        assertEquals(listOf("Trip Hop", "Downtempo"), parsed.genres)
        assertEquals(listOf("Calm"), parsed.moods)
        assertEquals(listOf("Ambient"), parsed.styles)
        assertEquals(listOf(92.5), parsed.bpms)
        assertTrue("must not consume audio payload", stream.available() >= 80_000)
    }

    @Test fun oggVorbisCommentsWorkAndAnEmptyVerifiedTagBlockIsKnownEmpty() {
        val head = byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(22)
        val comment = byteArrayOf(3) + "vorbis".toByteArray() + comments("STYLE=Indie Rock", "BPM=107")
        val all = page(88, 0, head, true) + page(88, 1, comment, false)
        val tags = requireNotNull(LocalEmbeddedTagParser.parse(all))
        assertEquals(listOf("Indie Rock"), tags.styles)
        assertEquals(listOf(107.0), tags.bpms)
        val empty = page(88, 0, head, true) +
            page(88, 1, byteArrayOf(3) + "vorbis".toByteArray() + comments("TITLE=Song"), false)
        assertEquals(emptyList<Double>(), requireNotNull(LocalEmbeddedTagParser.parse(empty)).bpms)
    }

    @Test fun opusTagsSpanningTwoContinuedPagesAreReassembledWithoutAudioReads() {
        val opusHead = "OpusHead".toByteArray() + ByteArray(11)
        val opusTags = "OpusTags".toByteArray() + comments(
            "GENRE=Electronic", "COMMENT=" + "x".repeat(240),
        )
        assertTrue(opusTags.size > 255 && opusTags.size < 510)
        fun raw(serial: Int, sequence: Int, flags: Int, laces: ByteArray, body: ByteArray): ByteArray =
            "OggS".toByteArray() + byteArrayOf(0, flags.toByte()) + ByteArray(8) +
                le32(serial) + le32(sequence) + ByteArray(4) +
                byteArrayOf(laces.size.toByte()) + laces + body
        val bytes = page(17, 0, opusHead, true) +
            raw(17, 1, 0, byteArrayOf(255.toByte()), opusTags.copyOfRange(0, 255)) +
            raw(17, 2, 1, byteArrayOf((opusTags.size - 255).toByte()),
                opusTags.copyOfRange(255, opusTags.size)) +
            ByteArray(250_000)
        val input = ByteArrayInputStream(bytes)
        val parsed = requireNotNull(LocalEmbeddedTagParser.readStream(input))
        assertEquals(listOf("Electronic"), parsed.genres)
        assertTrue(input.available() >= 250_000)
    }

    @Test fun rejectTruncatedPageForeignSerialAndMalformedCommentWithoutConvertingToMissing() {
        val head = page(7, 0, "OpusHead".toByteArray() + ByteArray(11), true)
        val comments = page(9, 1, "OpusTags".toByteArray() + comments("GENRE=Pop"), false)
        assertNull(LocalEmbeddedTagParser.parse(head + comments))
        assertNull(LocalEmbeddedTagParser.parse(head + comments.dropLast(3).toByteArray()))
        assertNull(LocalEmbeddedTagParser.parse(page(7, 0, "OpusHead".toByteArray() + ByteArray(11), true)))
    }
}
