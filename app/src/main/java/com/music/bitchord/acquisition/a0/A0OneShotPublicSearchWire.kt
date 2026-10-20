package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * A0 one-shot, read-only public search wire preparation.
 *
 * Search results are delivered on peer-initiated P TCP connections, NOT as a
 * list returned by the Soulseek login server. A returned matching filename
 * does NOT prove the file exists, is licensed or has a trusted checksum.
 *
 * No peer identity, path, IP address, packet body or local file is returned.
 * This codec performs no network I/O on its own: caller owns a short-lived
 * listener, socket timeouts and the authenticated server connection.
 */
internal object A0OneShotPublicSearchWire {
    const val TARGET_FILENAME = "Ringingsound.wav"
    // Nicotine+ deliberately strips punctuation from global search terms:
    // SoulseekQt may not return results for terms such as "Ringingsound.wav".
    // Keep the exact independently CC0-reviewed filename gate unchanged.
    const val SEARCH_TERM = "Ringingsound wav"
    private const val MAX_PEER_INIT_BYTES = 4096
    private const val MAX_PEER_SEARCH_BYTES = 1_048_576
    private const val MAX_PEER_NAME_BYTES = 128
    private const val MAX_PROBE_BYTES = 64L * 1024L * 1024L

    data class Observation(val untrustedFilenameMatches: Int)

    /** Tell the login server the port opened by the short-lived P listener. */
    fun setWaitPort(port: Int): ByteArray {
        require(port in 1..65535) { "Invalid A0 one-shot P port" }
        return frame(2, intLEBytes(port))
    }

    /** Submit exactly ONE immutable open-license reference filename query. */
    fun fileSearch(token: Int): ByteArray {
        require(token > 0) { "A0 search token must be positive" }
        val bytes = SEARCH_TERM.toByteArray(Charsets.UTF_8)
        return frame(26, intLEBytes(token) + intLEBytes(bytes.size) + bytes)
    }

    /**
     * Read exactly one direct P PeerInit then one matching SearchResponse from
     * an already accepted peer TCP socket. The username is compared only while
     * parsing and never returned. No code path acknowledges upload or initiates
     * download from an untrusted peer.
     *
     * Note: this does not authenticate the peer! PeerInit username is self-
     * reported; server-guided identity and indirect connect remain for A0.
     */
    fun readOneDirectPeerReply(input: InputStream, expectedSearchToken: Int): Observation {
        require(expectedSearchToken > 0)
        val peerInitLen = intLE(input)
        if (peerInitLen !in 14..MAX_PEER_INIT_BYTES) fail()
        val peerInit = ByteArrayInputStream(readExact(input, peerInitLen))
        if (peerInit.read() != 1) fail() // PeerInit, not PierceFirewall
        val claimedUser = string(peerInit)
        if (claimedUser.isBlank()) fail()
        if (string(peerInit) != "P") fail()
        if (intLE(peerInit) != 0 || peerInit.available() != 0) fail()

        val responseLen = intLE(input)
        if (responseLen !in 6..MAX_PEER_SEARCH_BYTES - 4) fail()
        val response = intLEBytes(responseLen) + readExact(input, responseLen)
        val parsed = A0SoulseekSearchResponseParser.parse(response, expectedSearchToken)
        if (parsed.peerUsername != claimedUser) fail()

        // This is deliberately only a COUNT of untrusted peer claims, not an
        // identity or download candidate. The original source SHA is pending.
        val count = if (parsed.hasFreeUploadSlot) {
            parsed.files.count {
                it.sizeBytes in 1..MAX_PROBE_BYTES &&
                    it.remoteFilename.replace('\\', '/').substringAfterLast('/') == TARGET_FILENAME
            }
        } else 0
        return Observation(count)
    }

    private fun frame(code: Int, payload: ByteArray): ByteArray {
        val body = intLEBytes(code) + payload
        return intLEBytes(body.size) + body
    }
    private fun intLEBytes(value: Int) = ByteArray(4) { i ->
        (value ushr (8 * i)).toByte()
    }
    private fun intLE(input: InputStream): Int {
        val bytes = readExact(input, 4)
        return (bytes[0].toInt() and 255) or
            ((bytes[1].toInt() and 255) shl 8) or
            ((bytes[2].toInt() and 255) shl 16) or
            ((bytes[3].toInt() and 255) shl 24)
    }
    private fun string(input: ByteArrayInputStream): String {
        val size = intLE(input)
        if (size !in 1..MAX_PEER_NAME_BYTES || size > input.available()) fail()
        return String(readExact(input, size), Charsets.UTF_8)
    }
    private fun readExact(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n <= 0) throw EOFException("A0 peer frame incomplete")
            read += n
        }
        return bytes
    }
    private fun fail(): Nothing = throw IOException("Unsolicited or malformed A0 peer search reply")
}
