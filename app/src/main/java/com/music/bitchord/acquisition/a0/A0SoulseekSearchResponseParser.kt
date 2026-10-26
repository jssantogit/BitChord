package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.InflaterInputStream

/**
 * A0 read-only, fail-closed decoder for a zlib-compressed Soulseek peer search response (code 9).
 *
 * This does not connect to a peer, download a file or prove real-network interoperability.
 * Protocol fields independently implemented using the Soulseek.NET wire-format reference:
 * https://github.com/jpdillingham/Soulseek.NET/blob/master/src/Messaging/Messages/Peer/SearchResponseFactory.cs
 */
internal object A0SoulseekSearchResponseParser {
    private const val MAX_WIRE_BYTES = 1_048_576
    private const val MAX_DECODED_BYTES = 2_097_152
    // Peers with large shared libraries can send thousands of files in one
    // reply. Keep both the inflated byte limit and the decoded entry limit;
    // never discard all of a peer's results merely because it has >512 files.
    private const val MAX_FILES = 4096
    private const val MAX_ATTRIBUTES = 32
    private const val MAX_FIELD_BYTES = 2_048
    private const val SEARCH_RESPONSE_CODE = 9

    fun parse(frame: ByteArray, expectedToken: Int): A0SoulseekSearchResponse {
        require(expectedToken > 0) { "A positive search token is required" }
        if (frame.size < 10 || frame.size > MAX_WIRE_BYTES) fail()
        val header = Fields(frame)
        val declaredLength = header.int32()
        if (declaredLength != frame.size - 4 || header.int32() != SEARCH_RESPONSE_CODE) fail()

        val raw = try {
            InflaterInputStream(ByteArrayInputStream(frame, 8, frame.size - 8)).use { zipped ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = zipped.read(buffer)
                    if (size < 0) break
                    if (size == 0) continue
                    if (size > MAX_DECODED_BYTES - output.size()) fail()
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
        } catch (_: IOException) {
            throw IOException("Invalid compressed peer search response")
        }

        val fields = Fields(raw)
        val username = fields.string()
        if (username.isBlank()) fail()
        val token = fields.int32()
        if (token != expectedToken) fail()

        var totalFiles = 0
        fun files(): List<A0SoulseekSearchFile> {
            val count = fields.int32()
            if (count < 0 || count > MAX_FILES - totalFiles) fail()
            totalFiles += count
            return List(count) {
                fields.u8() // File code: untrusted metadata, never canonical identity.
                val path = fields.string()
                if (path.isBlank()) fail()
                val size = fields.int64()
                if (size < 0L) fail()
                val extension = fields.string()
                val attributesCount = fields.int32()
                if (attributesCount !in 0..MAX_ATTRIBUTES) fail()
                val attributes = LinkedHashMap<Int, Int>(attributesCount)
                repeat(attributesCount) { attributes[fields.int32()] = fields.int32() }
                A0SoulseekSearchFile(path, size, extension, attributes.toMap())
            }
        }

        val publicFiles = files()
        val freeUploadSlot = fields.u8() != 0
        val speed = fields.int32()
        val queue = fields.int32()
        if (speed < 0 || queue < 0) fail()

        var lockedFiles = emptyList<A0SoulseekSearchFile>()
        if (fields.remaining > 0) {
            fields.int32() // Optional compatibility/reserved integer.
            if (fields.remaining > 0) lockedFiles = files()
        }
        if (fields.remaining != 0) fail()

        return A0SoulseekSearchResponse(
            peerUsername = username,
            token = token,
            files = publicFiles,
            lockedFiles = lockedFiles,
            hasFreeUploadSlot = freeUploadSlot,
            uploadSpeed = speed,
            queueLength = queue,
        )
    }

    private fun fail(): Nothing = throw IOException("Malformed or unsolicited peer search response")

    private class Fields(private val data: ByteArray) {
        private var position = 0
        val remaining: Int get() = data.size - position

        fun u8(): Int {
            if (remaining < 1) fail()
            return data[position++].toInt() and 255
        }
        fun int32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
        fun int64(): Long {
            var result = 0L
            for (i in 0 until 8) result = result or (u8().toLong() shl (i * 8))
            return result
        }
        fun string(): String {
            val size = int32()
            if (size < 0 || size > MAX_FIELD_BYTES || size > remaining) fail()
            val bytes = data.copyOfRange(position, position + size)
            position += size
            // UTF-8 first; malformed byte sequences use a reversible ISO-8859-1 fallback.
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
    }
}

internal data class A0SoulseekSearchFile(
    val remoteFilename: String,
    val sizeBytes: Long,
    val extension: String,
    val attributes: Map<Int, Int>,
)

internal data class A0SoulseekSearchResponse(
    val peerUsername: String,
    val token: Int,
    val files: List<A0SoulseekSearchFile>,
    val lockedFiles: List<A0SoulseekSearchFile>,
    val hasFreeUploadSlot: Boolean,
    val uploadSpeed: Int,
    val queueLength: Int,
)
