package com.music.bitchord.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMp4TagParserTest {
    private fun be32(n: Int): ByteArray = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(),
    )
    private fun atom(type: ByteArray, data: ByteArray): ByteArray =
        be32(data.size + 8) + type + data
    private fun atom(name: String, data: ByteArray): ByteArray =
        atom(name.toByteArray(Charsets.US_ASCII), data)
    private fun textItem(type: ByteArray, value: String): ByteArray =
        atom(type, atom("data", be32(1) + be32(0) + value.toByteArray(Charsets.UTF_8)))
    private fun freeform(kind: String, value: String): ByteArray =
        atom("----", atom("mean", be32(0) + "com.apple.iTunes".toByteArray()) +
            atom("name", be32(0) + kind.toByteArray()) +
            atom("data", be32(1) + be32(0) + value.toByteArray()))
    private fun m4a(vararg items: ByteArray): ByteArray =
        atom("ftyp", "M4A ".toByteArray() + be32(0) + "isom".toByteArray()) +
            atom("moov", atom("udta", atom("meta",
                be32(0) + atom("ilst", items.fold(ByteArray(0)) { a, b -> a + b }))))
    private fun read(data: ByteArray): LocalEmbeddedTags? =
        LocalMp4TagParser.parse(data.size.toLong()) { at, length ->
            if (at < 0L || at + length > data.size) null
            else data.copyOfRange(at.toInt(), at.toInt() + length)
        }

    @Test fun standardAndFreeformAppleTagsAreTypedWithoutEquatingGroupingToStyle() {
        val data = m4a(
            textItem(byteArrayOf(0xA9.toByte(), 'g'.code.toByte(), 'e'.code.toByte(), 'n'.code.toByte()), "Electronic"),
            atom("tmpo", atom("data", be32(0) + be32(0) + byteArrayOf(0, 127))),
            freeform("MOOD", "Bright"),
            freeform("STYLE", "House"),
            freeform("GENRE", "Downtempo"),
            textItem(byteArrayOf(0xA9.toByte(), 'g'.code.toByte(), 'r'.code.toByte(), 'p'.code.toByte()), "Album grouping"),
        )
        val tags = requireNotNull(read(data))
        assertEquals(listOf("Electronic", "Downtempo"), tags.genres)
        assertEquals(listOf("Bright"), tags.moods)
        assertEquals(listOf("House"), tags.styles)
        assertEquals(listOf(127.0), tags.bpms)
    }

    @Test fun moovAfterGiganticMdatIsLocatedBySeekWithoutReadingAudio() {
        val ftyp = atom("ftyp", "M4A ".toByteArray() + be32(0))
        val audioLength = 250_000_000
        val mdatHeader = be32(audioLength + 8) + "mdat".toByteArray()
        val moov = atom("moov", atom("meta",
            be32(0) + atom("ilst", freeform("BPM", "128.5") +
                freeform("STYLE", "Ambient"))))
        val total = ftyp.size.toLong() + audioLength + 8 + moov.size
        val tailStart = ftyp.size.toLong() + audioLength + 8
        var payloadBytesRead = 0
        val result = LocalMp4TagParser.parse(total) { offset, length ->
            val segment = when {
                offset >= 0 && offset + length <= ftyp.size ->
                    ftyp.copyOfRange(offset.toInt(), offset.toInt() + length)
                offset >= ftyp.size && offset + length <= ftyp.size + 8L ->
                    mdatHeader.copyOfRange((offset - ftyp.size).toInt(),
                        (offset - ftyp.size).toInt() + length)
                offset >= tailStart && offset + length <= total ->
                    moov.copyOfRange((offset - tailStart).toInt(),
                        (offset - tailStart).toInt() + length)
                else -> null
            }
            if (segment != null) payloadBytesRead += length
            segment
        }
        assertEquals(listOf(128.5), requireNotNull(result).bpms)
        assertEquals(listOf("Ambient"), result.styles)
        assertTrue(payloadBytesRead < 2_048)
    }

    @Test fun knownEmptyIlstDiffersFromMissingIlstAndCorruptAtoms() {
        assertEquals(emptyList<String>(), requireNotNull(read(m4a())).genres)
        assertNull(read(atom("ftyp", "M4A ".toByteArray()) + atom("moov", ByteArray(0))))
        val broken = m4a(freeform("MOOD", "Calm")).copyOf().also {
            it[it.lastIndex] = 0x7F
            it[0] = 0 // malformed initial atom size
        }
        assertNull(read(broken))
    }
}
