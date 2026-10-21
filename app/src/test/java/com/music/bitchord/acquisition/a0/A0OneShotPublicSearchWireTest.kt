package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream

/** Offline exact-filename search, peer responses remain untrusted. */
class A0OneShotPublicSearchWireTest {
    @Test fun setWaitPortAndSearchHaveCorrectServerFraming() {
        val port = A0OneShotPublicSearchWire.setWaitPort(41235)
        val p = ByteArrayInputStream(port)
        assertEquals(port.size - 4, int(p))
        assertEquals(2, int(p))
        assertEquals(41235, int(p))
        assertEquals(-1, p.read())

        val q = A0OneShotPublicSearchWire.fileSearch(27182, "newjeans - omg")
        val s = ByteArrayInputStream(q)
        assertEquals(q.size - 4, int(s))
        assertEquals(26, int(s))
        assertEquals(27182, int(s))
        assertEquals("newjeans omg", text(s)) // Nicotine+ removes punctuation before transmitting
        assertEquals(-1, s.read())
    }

    @Test fun typedArtistSongQueryPreservesIntentAndStripsPunctuationForSearch() {
        // Discovery request does not affect the independently reviewed CC0-only transfer gate.
        assertEquals("Ringingsound.wav", A0OneShotPublicSearchWire.TARGET_FILENAME)
        val wire = A0OneShotPublicSearchWire.fileSearch(120, "newjeans - omg")
        val q = ByteArrayInputStream(wire)
        int(q); int(q); int(q)
        assertEquals("newjeans omg", text(q))
        assertFalse(wire.toString(Charsets.ISO_8859_1).contains("newjeans - omg"))
    }

    @Test fun oneCorrelatedDirectPeerReplyCountsFilenameButNeverExposesPeerData() {
        val payload = peerInit("a_private_username", "P", 0) +
            searchReply("a_private_username", 27182, "secret_folder\\Ringingsound.wav")
        val result = A0OneShotPublicSearchWire.readOneDirectPeerReply(
            ByteArrayInputStream(payload), expectedSearchToken = 27182
        )
        assertEquals(1, result.untrustedFilenameMatches)
        assertEquals(1, result.untrustedResultCount)
        assertTrue(result.toString().contains("1"))
        assertFalse(result.toString().contains("a_private_username"))
        assertFalse(result.toString().contains("secret_folder"))
        assertFalse(result.toString().contains("Ringingsound.wav"))
    }

    @Test fun wrongSearchTokenOrClaimedUsernameMustNotCountAsAReply() {
        for (payload in listOf(
            peerInit("peer-A", "P", 0) + searchReply("peer-A", 123, "f\\Ringingsound.wav"),
            peerInit("peer-A", "P", 0) + searchReply("peer-B", 27182, "f\\Ringingsound.wav"),
            peerInit("peer-A", "F", 0) + searchReply("peer-A", 27182, "f\\Ringingsound.wav"),
            peerInit("peer-A", "P", 9) + searchReply("peer-A", 27182, "f\\Ringingsound.wav")
        )) {
            reject { A0OneShotPublicSearchWire.readOneDirectPeerReply(
                ByteArrayInputStream(payload), 27182
            ) }
        }
    }

    @Test fun badPeerInitOrOversizedFramesFailClosed() {
        for (bytes in listOf(
            byteArrayOf(0, 0, 0, 0),
            int(50000) + byteArrayOf(1),
            peerInit("a", "P", 0).copyOf(5),
            peerInit("a", "P", 0) + int(1_400_000) + byteArrayOf(9),
        )) {
            reject { A0OneShotPublicSearchWire.readOneDirectPeerReply(
                ByteArrayInputStream(bytes), 27182
            ) }
        }
    }

    @Test fun unrelatedOrLockedOnlyNamesNeverCountAsMatchingPublicFiles() {
        val reply = peerInit("a", "P", 0) + searchReply("a", 27182, "different.wav")
        val parsed = A0OneShotPublicSearchWire.readOneDirectPeerReply(
            ByteArrayInputStream(reply), 27182
        )
        assertEquals(0, parsed.untrustedFilenameMatches)
        assertEquals(1, parsed.untrustedResultCount) // Valid P reply but not a CC0 file
    }

    @Test fun invalidWaitPortAndTokensCannotGeneratePackets() {
        for (port in listOf(0, -1, 65536)) rejectIllegal {
            A0OneShotPublicSearchWire.setWaitPort(port)
        }
        for (token in listOf(0, -100)) rejectIllegal {
            A0OneShotPublicSearchWire.fileSearch(token, "search term")
        }
    }

    private fun peerInit(user: String, type: String, token: Int): ByteArray {
        val body = byteArrayOf(1) + field(user) + field(type) + int(token)
        return int(body.size) + body
    }

    private fun searchReply(user: String, token: Int, filename: String): ByteArray {
        val data = field(user) + int(token) + int(1) +
            byteArrayOf(1) + field(filename) + long(27000L) + field("wav") + int(0) +
            byteArrayOf(1) + int(120_000) + int(0) + int(0) + int(0)
        val compressed = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write(data) }
        }.toByteArray()
        return int(compressed.size + 4) + int(9) + compressed
    }

    private fun int(value: Int) = ByteArray(4) { (value ushr (8 * it)).toByte() }
    private fun long(value: Long) = ByteArray(8) { (value ushr (8 * it)).toByte() }
    private fun field(value: String): ByteArray {
        val b = value.toByteArray(Charsets.UTF_8)
        return int(b.size) + b
    }
    private fun int(input: ByteArrayInputStream): Int {
        val b = ByteArray(4)
        if (input.read(b) != 4) throw IOException("EOF")
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun text(input: ByteArrayInputStream): String {
        val n = int(input)
        require(n in 0..1024)
        val b = ByteArray(n)
        if (input.read(b) != n) throw IOException("EOF")
        return String(b, Charsets.UTF_8)
    }
    private fun reject(action: () -> Unit) {
        try { action(); fail("Malformed or unsolicited peer must be rejected") }
        catch (_: IOException) {}
    }
    private fun rejectIllegal(action: () -> Unit) {
        try { action(); fail("Invalid parameters must be rejected") }
        catch (_: IllegalArgumentException) {}
    }
}
