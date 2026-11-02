package com.music.bitchord.data.catalog

import java.nio.charset.Charset
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.io.InputStream

/**
 * Strict, bounded header parser; never parses audio frames or guesses missing tags.
 * Handles ID3v2.3/2.4 and FLAC Vorbis comments. Unsupported containers,
 * truncated headers and malformed evidence are UNKNOWN (null), not empty tags.
 */
internal data class LocalEmbeddedTags(
    val genres: List<String>,
    val moods: List<String>,
    val bpms: List<Double>,
    val styles: List<String>,
)

internal object LocalEmbeddedTagParser {
    const val MAX_HEADER_BYTES = 1_048_576
    private const val MAX_VALUES = 128
    private const val MAX_FIELD_LENGTH = 512

    /**
     * Read ONLY tag headers: 10-byte sniff for unknown formats, one ID3 tag,
     * or FLAC metadata blocks up to the Vorbis comments. No full-file reads.
     */
    fun readStream(stream: InputStream): LocalEmbeddedTags? {
        fun exact(count: Int): ByteArray? {
            if (count < 0 || count > MAX_HEADER_BYTES) return null
            val bytes = ByteArray(count)
            var read = 0
            while (read < count) {
                val got = stream.read(bytes, read, count - read)
                if (got <= 0) return null
                read += got
            }
            return bytes
        }
        val first = exact(4) ?: return null
        val isId3 = first[0] == 73.toByte() &&
            first[1] == 68.toByte() && first[2] == 51.toByte()
        val isFlac = first.contentEquals("fLaC".toByteArray(Charsets.US_ASCII))
        if (first.contentEquals("OggS".toByteArray(Charsets.US_ASCII))) {
            return LocalOggTagParser.read(SequenceInputStream(ByteArrayInputStream(first), stream))
        }
        if (isId3) {
            val rest = exact(6) ?: return null
            val prefix = first + rest
            val length = syncSafe(prefix, 6)?.toInt() ?: return null
            if (length > MAX_HEADER_BYTES - 10) return null
            return parse(prefix + (exact(length) ?: return null))
        }
        if (!isFlac) return null
        val out = ByteArrayOutputStream()
        out.write(first)
        while (out.size() < MAX_HEADER_BYTES - 4) {
            val head = exact(4) ?: return null
            val last = u(head, 0) and 128 != 0
            val type = u(head, 0) and 127
            val length = (u(head, 1) shl 16) or (u(head, 2) shl 8) or u(head, 3)
            if (length > MAX_HEADER_BYTES - out.size() - 4) return null
            out.write(head)
            out.write(exact(length) ?: return null)
            if (type == 4 || last) return parse(out.toByteArray())
        }
        return null
    }

    fun parse(header: ByteArray): LocalEmbeddedTags? {
        if (header.size < 4 || header.size > MAX_HEADER_BYTES) return null
        return when {
            header.size >= 10 && header[0] == 73.toByte() &&
                header[1] == 68.toByte() && header[2] == 51.toByte() ->
                parseId3(header)
            header[0] == 102.toByte() && header[1] == 76.toByte() &&
                header[2] == 97.toByte() && header[3] == 67.toByte() ->
                parseFlac(header)
            header[0] == 79.toByte() && header[1] == 103.toByte() &&
                header[2] == 103.toByte() && header[3] == 83.toByte() ->
                LocalOggTagParser.read(ByteArrayInputStream(header))
            else -> null
        }
    }

    /** Shared strict normalization for ID3, Vorbis and iTunes custom tags. */
    internal fun tagsFromPairs(pairs: List<Pair<String, String>>): LocalEmbeddedTags? {
        if (pairs.size > 128) return null
        val collector = Collector()
        for ((kind, value) in pairs) if (!collector.add(kind, value)) return null
        return collector.result()
    }

    private class Collector {
        val genres = linkedSetOf<String>()
        val moods = linkedSetOf<String>()
        val styles = linkedSetOf<String>()
        val bpms = linkedSetOf<Double>()

        fun add(kind: String, raw: String): Boolean {
            val normalized = kind.uppercase()
            // Ignore arbitrary large artwork/custom Vorbis comments instead of
            // failing all trustworthy genre/mood/tempo evidence in the file.
            if (normalized !in setOf("GENRE", "TCON", "MOOD", "TMOO",
                    "STYLE", "BPM", "TBPM")) return true
            val value = raw.trim().takeIf { it.length <= MAX_FIELD_LENGTH } ?: return false
            if (value.isEmpty()) return true
            when (normalized) {
                "GENRE", "TCON" -> genres.add(value)
                "MOOD", "TMOO" -> moods.add(value)
                "STYLE" -> styles.add(value)
                "BPM", "TBPM" -> {
                    val bpm = value.toDoubleOrNull()?.takeIf {
                        it.isFinite() && it > 0.0 && it <= 999.0
                    } ?: return false
                    bpms.add(bpm)
                }
            }
            return listOf(genres, moods, styles, bpms).all { it.size <= MAX_VALUES }
        }

        fun result() = LocalEmbeddedTags(
            genres.toList(), moods.toList(), bpms.toList(), styles.toList(),
        )
    }

    private fun u(bytes: ByteArray, at: Int) = bytes[at].toInt() and 255

    private fun be32(b: ByteArray, at: Int): Long =
        (u(b, at).toLong() shl 24) or (u(b, at + 1).toLong() shl 16) or
            (u(b, at + 2).toLong() shl 8) or u(b, at + 3).toLong()

    private fun syncSafe(b: ByteArray, at: Int): Long? {
        if ((0..3).any { u(b, at + it) and 128 != 0 }) return null
        return (u(b, at).toLong() shl 21) or (u(b, at + 1).toLong() shl 14) or
            (u(b, at + 2).toLong() shl 7) or u(b, at + 3).toLong()
    }

    private fun parseId3(bytes: ByteArray): LocalEmbeddedTags? {
        val version = u(bytes, 3)
        if (version !in 3..4 || u(bytes, 4) != 0) return null
        val flags = u(bytes, 5)
        // Do not claim a complete scan for unsynchronized, extended, or compressed data.
        if (flags != 0) return null
        val declared = syncSafe(bytes, 6) ?: return null
        if (declared > MAX_HEADER_BYTES || declared + 10 > bytes.size) return null
        val end = declared.toInt() + 10
        val collector = Collector()
        var pos = 10
        while (pos < end) {
            if (end - pos < 10) {
                if ((pos until end).all { bytes[it] == 0.toByte() }) break
                return null
            }
            if ((0..3).all { bytes[pos + it] == 0.toByte() }) {
                if ((pos until end).any { bytes[it] != 0.toByte() }) return null
                break
            }
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            if (id.any { !it.isUpperCase() && !it.isDigit() }) return null
            val len = (if (version == 4) syncSafe(bytes, pos + 4)
                else be32(bytes, pos + 4)) ?: return null
            val frameFlags = u(bytes, pos + 8) or u(bytes, pos + 9)
            if (frameFlags != 0 || len > MAX_HEADER_BYTES ||
                len > (end - pos - 10)) return null
            if (id in setOf("TCON", "TMOO", "TBPM", "TXXX")) {
                val values = readId3Text(bytes, pos + 10, len.toInt()) ?: return null
                if (id == "TXXX") {
                    val description = values.firstOrNull()?.trim()?.uppercase()
                    if (description in setOf("GENRE", "MOOD", "BPM", "STYLE")) {
                        for (value in values.drop(1)) if (!collector.add(description!!, value)) return null
                    }
                } else {
                    for (value in values) if (!collector.add(id, value)) return null
                }
            }
            pos += 10 + len.toInt()
        }
        return collector.result()
    }

    private fun readId3Text(bytes: ByteArray, start: Int, size: Int): List<String>? {
        if (size < 1 || size > 65_536 || start + size > bytes.size) return null
        val charset: Charset = when (u(bytes, start)) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return null
        }
        return String(bytes, start + 1, size - 1, charset)
            .split('\u0000')
            .map(String::trim)
            .takeIf { it.size <= MAX_VALUES + 1 }
    }

    private fun le32(b: ByteArray, at: Int): Long =
        u(b, at).toLong() or (u(b, at + 1).toLong() shl 8) or
            (u(b, at + 2).toLong() shl 16) or (u(b, at + 3).toLong() shl 24)

    private fun parseFlac(bytes: ByteArray): LocalEmbeddedTags? {
        var pos = 4
        while (pos + 4 <= bytes.size) {
            val last = u(bytes, pos) and 128 != 0
            val type = u(bytes, pos) and 127
            val size = (u(bytes, pos + 1) shl 16) or
                (u(bytes, pos + 2) shl 8) or u(bytes, pos + 3)
            pos += 4
            if (size < 0 || size > MAX_HEADER_BYTES || pos + size > bytes.size) return null
            if (type == 4) return parseVorbisComments(bytes, pos, size)
            pos += size
            if (last) return LocalEmbeddedTags(emptyList(), emptyList(), emptyList(), emptyList())
        }
        return null
    }

    internal fun parseVorbisCommentBlock(data: ByteArray): LocalEmbeddedTags? =
        parseVorbisComments(data, 0, data.size)

    private fun parseVorbisComments(bytes: ByteArray, start: Int, size: Int): LocalEmbeddedTags? {
        val end = start + size
        var pos = start
        fun nextField(): ByteArray? {
            if (pos + 4 > end) return null
            val length = le32(bytes, pos)
            pos += 4
            if (length > 65_536 || length > end - pos) return null
            val value = bytes.copyOfRange(pos, pos + length.toInt())
            pos += length.toInt()
            return value
        }
        nextField() ?: return null // Vendor string.
        if (pos + 4 > end) return null
        val count = le32(bytes, pos)
        pos += 4
        if (count > 10_000) return null
        val collector = Collector()
        repeat(count.toInt()) {
            val raw = nextField() ?: return null
            val text = String(raw, Charsets.UTF_8)
            val key = text.substringBefore('=', "").trim().uppercase()
            val value = text.substringAfter('=', "")
            if (!collector.add(key, value)) return null
        }
        return collector.result()
    }
}
