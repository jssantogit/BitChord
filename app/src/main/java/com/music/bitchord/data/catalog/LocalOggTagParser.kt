package com.music.bitchord.data.catalog

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Ogg page/payload reader: only the identification and comment packets, never audio packets. */
internal object LocalOggTagParser {
    fun read(stream: InputStream): LocalEmbeddedTags? {
        var consumed = 0
        fun exact(n: Int): ByteArray? {
            if (n < 0 || n > LocalEmbeddedTagParser.MAX_HEADER_BYTES - consumed) return null
            val result = ByteArray(n)
            var pos = 0
            while (pos < n) {
                val count = stream.read(result, pos, n - pos)
                if (count <= 0) return null
                pos += count
            }
            consumed += n
            return result
        }
        fun byte(b: Byte) = b.toInt() and 255
        fun le32(bytes: ByteArray, at: Int): Long =
            byte(bytes[at]).toLong() or (byte(bytes[at + 1]).toLong() shl 8) or
                (byte(bytes[at + 2]).toLong() shl 16) or (byte(bytes[at + 3]).toLong() shl 24)

        var streamSerial: Long? = null
        var lastSequence: Long? = null
        var kind: String? = null
        var packetNumber = 0
        val packet = ByteArrayOutputStream()
        repeat(32) { index ->
            val header = exact(27) ?: return null
            if (!header.copyOfRange(0, 4).contentEquals("OggS".toByteArray()) ||
                byte(header[4]) != 0) return null
            val flags = byte(header[5])
            if (flags and 0xF8 != 0 || index == 0 && flags and 2 == 0) return null
            val serial = le32(header, 14)
            val sequence = le32(header, 18)
            if (streamSerial != null && streamSerial != serial) return null
            if (lastSequence != null && sequence != lastSequence!! + 1L) return null
            if ((flags and 1 != 0) != (packet.size() != 0)) return null
            streamSerial = serial
            lastSequence = sequence
            val laces = exact(byte(header[26])) ?: return null
            val bodySize = laces.sumOf(::byte)
            val body = exact(bodySize) ?: return null
            var offset = 0
            laces.forEach { lace ->
                val n = byte(lace)
                if (packet.size() + n > LocalEmbeddedTagParser.MAX_HEADER_BYTES) return null
                packet.write(body, offset, n)
                offset += n
                if (n < 255) {
                    val bytes = packet.toByteArray()
                    packet.reset()
                    if (packetNumber++ == 0) {
                        kind = when {
                            bytes.size >= 8 &&
                                bytes.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray()) -> "opus"
                            bytes.size >= 7 && bytes[0] == 1.toByte() &&
                                bytes.copyOfRange(1, 7).contentEquals("vorbis".toByteArray()) -> "vorbis"
                            else -> return null
                        }
                    } else if (packetNumber == 2) {
                        val comment = when (kind) {
                            "opus" -> bytes.takeIf {
                                it.size >= 8 &&
                                    it.copyOfRange(0, 8).contentEquals("OpusTags".toByteArray())
                            }?.copyOfRange(8, bytes.size)
                            "vorbis" -> bytes.takeIf {
                                it.size >= 7 && it[0] == 3.toByte() &&
                                    it.copyOfRange(1, 7).contentEquals("vorbis".toByteArray())
                            }?.copyOfRange(7, bytes.size)
                            else -> null
                        } ?: return null
                        return LocalEmbeddedTagParser.parseVorbisCommentBlock(comment)
                    }
                }
            }
        }
        return null
    }
}
