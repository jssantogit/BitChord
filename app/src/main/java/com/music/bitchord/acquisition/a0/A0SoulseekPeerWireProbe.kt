package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * A0 only: deterministic Soulseek peer message framing for LOCALHOST-only test fixtures.
 * No socket connector, peer discovery, real handshake, file stream or user-facing entrypoint.
 * All messages are unauthenticated/untrusted; tokens are correlation values, not credentials.
 * Wire reference: Soulseek.NET PeerInit, TransferRequest, TransferResponse, MessageBuilder.
 */
internal object A0SoulseekPeerWireProbe {
    private const val MAX_FRAME_BYTES = 4096
    private const val MAX_FILENAME_BYTES = 1024
    private const val MAX_USERNAME_BYTES = 128
    private const val MAX_TEST_TRANSFER_BYTES = 512L * 1024L * 1024L
    private const val INIT_PEER = 1 // One-byte initialization code
    private const val PEER_TRANSFER_REQUEST = 40 // Four-byte peer message code
    private const val PEER_TRANSFER_RESPONSE = 41
    private const val DIRECTION_DOWNLOAD = 0

    data class TransferDecision(val allowed: Boolean, val peerReportedByteCount: Long?)

    /** Framing only. The 'P' peer-message channel is not a file data channel. */
    fun peerInit(username: String, token: Int): ByteArray {
        require(token > 0) { "A0 requires a positive peer correlation token" }
        val name = utf8Field(username, MAX_USERNAME_BYTES)
        return frame(byteArrayOf(INIT_PEER.toByte()) + name + utf8Field("P", 1) + intLE(token))
    }

    /** Serializes only download direction. Remote filename is never a local path. */
    fun downloadRequest(remoteFilename: String, token: Int, advertisedByteCount: Long): ByteArray {
        require(token > 0) { "A0 requires a positive transfer correlation token" }
        require(advertisedByteCount in 44..MAX_TEST_TRANSFER_BYTES) {
            "A0 transfer size is outside its bounded test-only staging scope"
        }
        val filename = utf8Field(remoteFilename, MAX_FILENAME_BYTES)
        return frame(intLE(PEER_TRANSFER_REQUEST) + intLE(DIRECTION_DOWNLOAD) +
            intLE(token) + filename + longLE(advertisedByteCount))
    }

    /**
     * Parses only a single bounded response on a caller-owned stream.
     * Caller must set transport deadlines; no peer network activity is initiated here.
     * An allowed response with omitted/zero peer size reports null, not a fabricated size.
     * Rejection reasons are discarded: peer-supplied text must not escape in UI or logs.
     */
    fun readTransferDecision(
        input: InputStream,
        expectedToken: Int,
        advertisedByteCount: Long,
    ): TransferDecision {
        require(expectedToken > 0)
        require(advertisedByteCount in 44..MAX_TEST_TRANSFER_BYTES)
        val declaredSize = intLE(input)
        if (declaredSize !in 9..MAX_FRAME_BYTES) invalid()
        val message = ByteArrayInputStream(readFully(input, declaredSize))
        if (intLE(message) != PEER_TRANSFER_RESPONSE) invalid()
        if (intLE(message) != expectedToken) invalid()
        val accepted = message.read()
        if (accepted != 0 && accepted != 1) invalid()
        val decision = if (accepted == 1) {
            when (message.available()) {
                0 -> TransferDecision(allowed = true, peerReportedByteCount = null)
                8 -> {
                    val size = longLE(message)
                    if (size < 0 || (size != 0L && size != advertisedByteCount)) invalid()
                    TransferDecision(allowed = true, peerReportedByteCount = size.takeIf { it > 0 })
                }
                else -> invalid()
            }
        } else {
            val textSize = intLE(message)
            if (textSize !in 0..1024 || textSize != message.available()) invalid()
            // Do not expose a remote reason or keep it in an object.
            readFully(message, textSize)
            TransferDecision(allowed = false, peerReportedByteCount = null)
        }
        if (message.available() != 0) invalid()
        return decision
    }

    private fun utf8Field(value: String, limit: Int): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= limit && !value.contains('\u0000')) {
            "Invalid A0 peer protocol field"
        }
        return intLE(bytes.size) + bytes
    }

    private fun frame(payload: ByteArray): ByteArray {
        require(payload.size in 1..MAX_FRAME_BYTES) { "Invalid A0 frame" }
        return intLE(payload.size) + payload
    }

    private fun intLE(value: Int): ByteArray = ByteArray(4) { index ->
        (value ushr (index * 8)).toByte()
    }
    private fun longLE(value: Long): ByteArray = ByteArray(8) { index ->
        (value ushr (index * 8)).toByte()
    }
    private fun intLE(stream: InputStream): Int {
        val bytes = readFully(stream, 4)
        return (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8) or
            ((bytes[2].toInt() and 255) shl 16) or ((bytes[3].toInt() and 255) shl 24)
    }
    private fun longLE(stream: InputStream): Long {
        val bytes = readFully(stream, 8)
        var result = 0L
        for (index in bytes.indices) result = result or
            ((bytes[index].toLong() and 255L) shl (index * 8))
        return result
    }
    private fun readFully(stream: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = stream.read(bytes, offset, size - offset)
            if (read < 0) throw EOFException("Incomplete A0 peer protocol frame")
            if (read == 0) throw IOException("A0 peer protocol stream stopped progressing")
            offset += read
        }
        return bytes
    }
    private fun invalid(): Nothing = throw IOException("Malformed or unsolicited A0 peer protocol response")
}
