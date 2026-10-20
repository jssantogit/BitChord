package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress

/**
 * Server-guided indirect P connection setup for the devDebug A0 one-shot search.
 * Inputs (including addresses relayed by legacy cleartext server TCP) are untrusted.
 * No disk, sharing, transfers, or background service.
 * Reference: Nicotine+ SLSKPROTOCOL, server code 18 and PeerInit code 0.
 */
internal object A0IndirectSearchWire {
    private const val MAX_SERVER_FRAME = 1_048_576
    private const val MAX_USERNAME_BYTES = 30

    internal data class Offer(
        val username: String,
        val address: InetAddress,
        val port: Int,
        val token: Int
    )

    /** Consume a complete server frame. Other server events are ignored. */
    fun readServerEvent(input: InputStream): Offer? {
        val size = le32(input)
        if (size !in 4..MAX_SERVER_FRAME) invalid()
        val code = le32(input)
        val payload = ByteArrayInputStream(exact(input, size - 4))
        if (code == 41) throw IOException("A0 login replaced; search session ended")
        if (code != 18) return null

        val username = field(payload)
        val type = field(payload)
        val ip = exact(payload, 4).reversedArray() // Nicotine+ unpack_ip uses reverse byte order.
        val port = le32(payload)
        val token = le32(payload)
        val privileged = payload.read()
        val obfuscation = le32(payload)
        val obfuscatedPort = le32(payload)
        if (username.isEmpty() || username.length > MAX_USERNAME_BYTES ||
            username.any { it.code < 33 || it.code > 126 } ||
            type != "P" || token <= 0 || port !in 1..65535 ||
            privileged !in 0..1 || obfuscation != 0 || obfuscatedPort != 0 ||
            payload.available() != 0 || !publicIpv4(ip)
        ) invalid()
        return Offer(username, InetAddress.getByAddress(ip), port, token)
    }

    /** Read one code-9 response on an outbound P socket after PierceFirewall. */
    fun readIndirectReply(
        input: InputStream,
        expectedSearchToken: Int,
        expectedPeerUsername: String
    ): A0OneShotPublicSearchWire.Observation {
        require(expectedSearchToken > 0 && expectedPeerUsername.isNotBlank())
        val length = le32(input)
        if (length !in 6..1_048_572) invalid()
        val frame = le32Bytes(length) + exact(input, length)
        val reply = A0SoulseekSearchResponseParser.parse(frame, expectedSearchToken)
        if (reply.peerUsername != expectedPeerUsername) invalid()
        val count = if (reply.hasFreeUploadSlot) reply.files.count { file ->
            file.sizeBytes in 1..(64L * 1024L * 1024L) &&
                file.remoteFilename.replace('\\', '/').substringAfterLast('/') ==
                    A0OneShotPublicSearchWire.TARGET_FILENAME
        } else 0
        return A0OneShotPublicSearchWire.Observation(count)
    }

    /** Indirect P socket first message: uint32 length=5, uint8 code=0, uint32 token. */
    fun pierceFirewall(token: Int): ByteArray {
        require(token > 0) { "A0 indirect token must be positive" }
        return le32Bytes(5) + byteArrayOf(0) + le32Bytes(token)
    }

    private fun publicIpv4(ip: ByteArray): Boolean {
        if (ip.size != 4) return false
        val a = ip[0].toInt() and 255
        val b = ip[1].toInt() and 255
        val c = ip[2].toInt() and 255
        return a in 1..223 && a != 10 && a != 127 &&
            !(a == 100 && b in 64..127) &&
            !(a == 169 && b == 254) &&
            !(a == 172 && b in 16..31) &&
            !(a == 192 && (b == 0 || b == 168)) &&
            !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) &&
            !(a == 203 && b == 0 && c == 113)
    }

    private fun field(input: ByteArrayInputStream): String {
        val size = le32(input)
        if (size !in 1..MAX_USERNAME_BYTES || size > input.available()) invalid()
        val bytes = exact(input, size)
        if (bytes.any { (it.toInt() and 255) !in 33..126 }) invalid()
        return String(bytes, Charsets.US_ASCII)
    }

    private fun le32Bytes(value: Int) = ByteArray(4) { i -> (value ushr (i * 8)).toByte() }
    private fun le32(input: InputStream): Int {
        val b = exact(input, 4)
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun exact(input: InputStream, n: Int): ByteArray {
        val bytes = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val received = input.read(bytes, offset, n - offset)
            if (received <= 0) throw EOFException("A0 incomplete server event")
            offset += received
        }
        return bytes
    }
    private fun invalid(): Nothing = throw IOException("Unsupported A0 indirect P offer")
}
