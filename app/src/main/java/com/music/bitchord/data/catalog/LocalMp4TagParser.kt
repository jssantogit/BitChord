package com.music.bitchord.data.catalog

import java.nio.charset.StandardCharsets

/**
 * Seek-only ISO BMFF metadata walker. Skips mdat/trak/sample tables by their
 * declared sizes (never reads audio). Reads at most 1 MiB of atom headers and
 * small tag payloads, regardless of file size or moov placement.
 */
internal object LocalMp4TagParser {
    private const val MAX_BYTES = LocalEmbeddedTagParser.MAX_HEADER_BYTES
    private const val MAX_NODES = 4096
    private const val MAX_ITEM = 65536

    fun parse(length: Long, readAt: (Long, Int) -> ByteArray?): LocalEmbeddedTags? {
        if (length < 16L) return null
        var budget = 0
        var nodes = 0
        fun read(position: Long, count: Int): ByteArray? {
            if (count < 0 || count > MAX_BYTES - budget ||
                position < 0 || position > length ||
                count.toLong() > length - position) return null
            val bytes = readAt(position, count)?.takeIf { it.size == count } ?: return null
            budget += count
            return bytes
        }
        fun num32(b: ByteArray, pos: Int): Long =
            ((b[pos].toLong() and 255L) shl 24) or
                ((b[pos + 1].toLong() and 255L) shl 16) or
                ((b[pos + 2].toLong() and 255L) shl 8) or
                (b[pos + 3].toLong() and 255L)
        fun num64(b: ByteArray): Long {
            val hi = num32(b, 0)
            val lo = num32(b, 4)
            if (hi > 0x7FFF_FFFFL) return -1L
            return (hi shl 32) or lo
        }
        data class Atom(val type: String, val start: Long, val end: Long, val data: Long)
        fun atoms(start: Long, end: Long): List<Atom>? {
            val list = mutableListOf<Atom>()
            var pos = start
            while (pos < end) {
                if (++nodes > MAX_NODES || end - pos < 8L) return null
                val h = read(pos, 8) ?: return null
                var size = num32(h, 0)
                val type = String(h, 4, 4, StandardCharsets.ISO_8859_1)
                var headerSize = 8L
                if (size == 1L) {
                    size = num64(read(pos + 8, 8) ?: return null)
                    headerSize = 16L
                } else if (size == 0L) size = end - pos
                if (size < headerSize || size > end - pos) return null
                list += Atom(type, pos, pos + size, pos + headerSize)
                pos += size
            }
            return list
        }

        // MP4/M4A identification before treating an empty ilst as known absent.
        val root = atoms(0L, length) ?: return null
        if (root.firstOrNull()?.type != "ftyp") return null
        val moov = root.firstOrNull { it.type == "moov" } ?: return null
        val pairs = mutableListOf<Pair<String, String>>()
        var foundIlst = false

        fun dataValue(atom: Atom): Pair<Int, ByteArray>? {
            val size = atom.end - atom.data
            if (size !in 8..MAX_ITEM.toLong()) return null
            val content = read(atom.data, size.toInt()) ?: return null
            return (num32(content, 0).toInt() and 0xFF_FFFF) to
                content.copyOfRange(8, content.size)
        }
        fun utf8(bytes: ByteArray): String? {
            val text = String(bytes, Charsets.UTF_8)
            if (text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return text
            return null
        }
        fun parseItem(item: Atom): Boolean {
            val children = atoms(item.data, item.end) ?: return false
            val supported = when (item.type) {
                "©gen" -> "GENRE"
                "tmpo" -> "BPM"
                "----" -> "FREEFORM"
                else -> return true // Never confuse grouping (©grp) with style.
            }
            if (supported == "FREEFORM") {
                val named = children.firstOrNull { it.type == "name" } ?: return false
                val namespace = children.firstOrNull { it.type == "mean" } ?: return false
                fun fullBoxText(a: Atom): String? {
                    val size = a.end - a.data
                    if (size !in 4..MAX_ITEM.toLong()) return null
                    return utf8(read(a.data + 4, size.toInt() - 4) ?: return null)
                }
                if (fullBoxText(namespace) != "com.apple.iTunes") return true
                val key = fullBoxText(named)?.uppercase() ?: return false
                if (key !in setOf("GENRE", "MOOD", "STYLE", "BPM")) return true
                val atom = children.firstOrNull { it.type == "data" } ?: return false
                val (type, bytes) = dataValue(atom) ?: return false
                if (type != 1) return false
                val value = utf8(bytes) ?: return false
                pairs += key to value
                return true
            }
            for (atom in children.filter { it.type == "data" }) {
                val (type, bytes) = dataValue(atom) ?: return false
                if (supported == "BPM") {
                    // iTunes tmpo is a big-endian 16-bit integer, not a string.
                    if (type != 0 || bytes.size != 2) return false
                    val tempo = ((bytes[0].toInt() and 255) shl 8) or
                        (bytes[1].toInt() and 255)
                    if (tempo > 999) return false
                    if (tempo > 0) pairs += "BPM" to tempo.toString()
                } else {
                    if (type != 1) return false
                    pairs += supported to (utf8(bytes) ?: return false)
                }
            }
            return true
        }

        fun findIlst(atom: Atom, depth: Int): Boolean {
            if (depth > 4) return false
            val childrenStart = atom.data + if (atom.type == "meta") 4 else 0
            if (childrenStart > atom.end) return false
            val children = atoms(childrenStart, atom.end) ?: return false
            for (child in children) {
                when (child.type) {
                    "ilst" -> {
                        if (foundIlst) return false
                        foundIlst = true
                        val items = atoms(child.data, child.end) ?: return false
                        for (item in items) if (!parseItem(item)) return false
                    }
                    "udta", "meta" -> if (!findIlst(child, depth + 1)) return false
                }
            }
            return true
        }
        if (!findIlst(moov, 0) || !foundIlst) return null
        return LocalEmbeddedTagParser.tagsFromPairs(pairs)
    }
}
