package com.music.bitchord.data.catalog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEmbeddedTagParserTest {
    private fun be32(n: Int): ByteArray = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(),
        (n ushr 8).toByte(), n.toByte(),
    )
    private fun le32(n: Int): ByteArray = byteArrayOf(
        n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(),
        (n ushr 24).toByte(),
    )
    private fun frame(name: String, value: String): ByteArray {
        val data = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
        return name.toByteArray(Charsets.US_ASCII) + be32(data.size) +
            byteArrayOf(0, 0) + data
    }
    private fun id3(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, next -> acc + next }
        val size = body.size
        return "ID3".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(3, 0, 0, (size ushr 21).toByte(),
                (size ushr 14 and 127).toByte(), (size ushr 7 and 127).toByte(),
                (size and 127).toByte()) + body
    }
    private fun flac(vararg comments: String): ByteArray {
        val block = ByteArrayOutputStream()
        val vendor = "Test".toByteArray(Charsets.UTF_8)
        block.write(le32(vendor.size)); block.write(vendor)
        block.write(le32(comments.size))
        comments.forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            block.write(le32(bytes.size)); block.write(bytes)
        }
        val payload = block.toByteArray()
        return "fLaC".toByteArray(Charsets.US_ASCII) +
            byteArrayOf((128 or 4).toByte(),
                (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(),
                payload.size.toByte()) + payload
    }

    @Test fun readsId3v23GenreMoodTempoAndCustomStyleWithoutDecodingAudio() {
        val input = id3(
            frame("TCON", "Dream Pop"),
            frame("TMOO", "Calm"),
            frame("TBPM", "128.5"),
            frame("TXXX", "STYLE\u0000Shoegaze"),
        )
        val tags = requireNotNull(LocalEmbeddedTagParser.parse(input))
        assertEquals(listOf("Dream Pop"), tags.genres)
        assertEquals(listOf("Calm"), tags.moods)
        assertEquals(listOf(128.5), tags.bpms)
        assertEquals(listOf("Shoegaze"), tags.styles)
    }

    @Test fun repeatedVorbisCommentsRemainMultiValuedAndExplicitAbsenceIsNotUnknown() {
        val tags = requireNotNull(LocalEmbeddedTagParser.parse(flac(
            "GENRE=K-Pop", "GENRE=Dream Pop", "MOOD=Bright", "BPM=105",
            "STYLE=Electronic", "STYLE=Ambient",
        )))
        assertEquals(listOf("K-Pop", "Dream Pop"), tags.genres)
        assertEquals(listOf("Bright"), tags.moods)
        assertEquals(listOf(105.0), tags.bpms)
        assertEquals(listOf("Electronic", "Ambient"), tags.styles)
        val empty = requireNotNull(LocalEmbeddedTagParser.parse(flac("TITLE=Song")))
        assertEquals(emptyList<String>(), empty.genres)
        assertEquals(emptyList<String>(), empty.moods)
        assertEquals(emptyList<Double>(), empty.bpms)
    }

    @Test fun streamingReaderConsumesOnlyMetadataNotAudioPayload() {
        val bytes = id3(frame("TMOO", "Energetic"), frame("TBPM", "134"))
        val source = ByteArrayInputStream(bytes + ByteArray(2_000_000) { 99 })
        val result = requireNotNull(LocalEmbeddedTagParser.readStream(source))
        assertEquals(listOf("Energetic"), result.moods)
        assertEquals(listOf(134.0), result.bpms)
        assertTrue(source.available() >= 2_000_000)
        val flacSource = ByteArrayInputStream(
            flac("STYLE=House", "BPM=119.5") + ByteArray(50_000) { 33 },
        )
        assertEquals(listOf("House"),
            requireNotNull(LocalEmbeddedTagParser.readStream(flacSource)).styles)
        assertTrue(flacSource.available() >= 50_000)
    }

    @Test fun corruptOrUnsupportedHeadersAreUnknownNeverKnownMissing() {
        assertNull(LocalEmbeddedTagParser.parse(byteArrayOf(1, 2, 3)))
        assertNull(LocalEmbeddedTagParser.parse("ID3".toByteArray()))
        assertNull(LocalEmbeddedTagParser.parse(flac("BPM=not a number")))
        assertNull(LocalEmbeddedTagParser.parse(id3(frame("TBPM", "-42"))))
        val truncated = id3(frame("TMOO", "Calm")).dropLast(3).toByteArray()
        assertNull(LocalEmbeddedTagParser.parse(truncated))
    }
}
