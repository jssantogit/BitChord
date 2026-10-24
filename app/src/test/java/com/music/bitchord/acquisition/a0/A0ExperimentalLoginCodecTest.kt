package com.music.bitchord.acquisition.a0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

/** Offline public-server login protocol vectors; NEVER sends real credentials. */
class A0ExperimentalLoginCodecTest {

    @Test
    fun testOnlyClientIdentityNeverReusesReservedSoulseekNetVersion() {
        assertEquals(177, A0ExperimentalLoginCodec.EXPERIMENTAL_MAJOR)
        assertTrue(A0ExperimentalLoginCodec.BITCHORD_MINOR > 0)
        assertFalse(A0ExperimentalLoginCodec.EXPERIMENTAL_MAJOR == 170)
        assertFalse(A0ExperimentalLoginCodec.BITCHORD_MINOR == 1201)
    }

    @Test
    fun encodesOneCorrectBoundedSoulseekLoginPacket() {
        val password = "owned-test-pass".toCharArray()
        val wire = A0ExperimentalLoginCodec.encode("owned-test-user", password)
        val frame = ByteArrayInputStream(wire)
        assertEquals(wire.size - 4, int(frame))
        assertEquals(1, int(frame))
        assertEquals("owned-test-user", text(frame))
        assertEquals("owned-test-pass", text(frame))
        assertEquals(177, int(frame))
        val md5 = MessageDigest.getInstance("MD5")
            .digest("owned-test-userowned-test-pass".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(md5, text(frame))
        assertEquals(A0ExperimentalLoginCodec.BITCHORD_MINOR, int(frame))
        assertEquals(-1, frame.read())
        wire.fill(0)
        password.fill('\u0000')
    }

    @Test
    fun approvedResponseReturnsOnlyGenericSuccessWithoutServerPayload() {
        val response = frame(1, byteArrayOf(1) + field("sensitive motd") +
            byteArrayOf(1, 2, 3, 4) + field("md5-hidden") + byteArrayOf(0))
        assertTrue(A0ExperimentalLoginCodec.readResponse(ByteArrayInputStream(response)))
    }

    @Test
    fun rejectionNeverExposesTheRemoteReason() {
        val response = frame(1, byteArrayOf(0) + field("name-or-password-secret-reason"))
        try {
            A0ExperimentalLoginCodec.readResponse(ByteArrayInputStream(response))
            fail("Expected login rejection")
        } catch (ex: A0ExperimentalLoginCodec.LoginDenied) {
            assertFalse(ex.message.orEmpty().contains("secret"))
            assertFalse(ex.message.orEmpty().contains("reason"))
        }
    }

    @Test
    fun oversizedTruncatedOrUnrecognizedServerResponseFailsClosed() {
        val invalid = listOf(
            le32(999999),
            le32(12) + le32(1) + byteArrayOf(1),
            frame(99, byteArrayOf(1) + field("hello")),
            frame(1, byteArrayOf(9) + field("hello"))
        )
        for (bytes in invalid) {
            try {
                A0ExperimentalLoginCodec.readResponse(ByteArrayInputStream(bytes))
                fail("Malformed server response must be refused")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun refusesInvalidCredentialsBeforeSerializing() {
        val invalidUsers = listOf("", "\u0000username", "x".repeat(257))
        for (user in invalidUsers) {
            expectIllegal { A0ExperimentalLoginCodec.encode(user, "valid".toCharArray()) }
        }
        expectIllegal { A0ExperimentalLoginCodec.encode("valid", charArrayOf()) }
        expectIllegal { A0ExperimentalLoginCodec.encode("valid", CharArray(257) { 'p' }) }
        expectIllegal { A0ExperimentalLoginCodec.encode("valid", "bad\u0000pass".toCharArray()) }
    }

    private fun frame(code: Int, payload: ByteArray): ByteArray =
        le32(payload.size + 4) + le32(code) + payload
    private fun field(v: String): ByteArray {
        val value = v.toByteArray(Charsets.UTF_8)
        return le32(value.size) + value
    }
    private fun le32(n: Int): ByteArray = ByteArray(4) { (n ushr (it * 8)).toByte() }
    private fun int(input: ByteArrayInputStream): Int {
        val b = ByteArray(4)
        if (input.read(b) != 4) throw IOException("EOF")
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun text(input: ByteArrayInputStream): String {
        val length = int(input)
        if (length !in 0..4096) throw IOException("Out of bounds")
        val b = ByteArray(length)
        if (input.read(b) != length) throw IOException("EOF")
        return String(b, Charsets.UTF_8)
    }
    private fun expectIllegal(block: () -> Unit) {
        try { block(); fail("Expected preflight argument error") } catch (_: IllegalArgumentException) {}
    }
}
