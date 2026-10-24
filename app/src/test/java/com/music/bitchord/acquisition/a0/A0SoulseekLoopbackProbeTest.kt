package com.music.bitchord.acquisition.a0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Contract fixture: real localhost TCP sockets, not a real Soulseek server or transfer. */
class A0SoulseekLoopbackProbeTest {
    @Test(timeout = 12000)
    fun loginAndSearchUseSoulseekServerFramingThenCloseSocket() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val exchange = pool.submit<String> {
                    server.accept().use { peer ->
                        peer.soTimeout = 4000
                        val login = ByteArrayInputStream(readFrame(peer.getInputStream()))
                        assertEquals(1, readInt(login)) // server login message code
                        assertEquals("probe-user", readString(login))
                        assertEquals("probe-pass", readString(login))
                        assertEquals(170, readInt(login)) // protocol major version, not a registered BitChord identity
                        assertEquals("b32f4de8347329603db8c8ad7a0a9720", readString(login))
                        assertEquals(1201, readInt(login))
                        assertEquals(-1, login.read())
                        peer.getOutputStream().write(
                            frame(1, byteArrayOf(1) + stringBytes("welcome") +
                                byteArrayOf(127, 0, 0, 1) + stringBytes("hash") + byteArrayOf(0))
                        )
                        peer.getOutputStream().flush()
                        val search = ByteArrayInputStream(readFrame(peer.getInputStream()))
                        assertEquals(26, readInt(search)) // server FileSearch code
                        assertEquals(42, readInt(search))
                        assertEquals("own composition", readString(search))
                        assertEquals(-1, search.read())
                        assertEquals(-1, peer.getInputStream().read()) // session closes after use
                    }
                    "local_tcp_exercised"
                }
                A0SoulseekLoopbackProbe().use { probe ->
                    probe.connect("127.0.0.1", server.localPort, "probe-user", "probe-pass", 1201)
                    assertTrue(probe.isAuthenticated)
                    probe.search("own composition", 42)
                }
                assertEquals("local_tcp_exercised", exchange.get(7, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun blocksAllNonLoopbackEndpointsBeforeOpeningSocket() {
        A0SoulseekLoopbackProbe().use { probe ->
            for (host in listOf("example.com", "1.1.1.1", "192.168.0.2", "soulseek.org")) {
                try {
                    probe.connect(host, 2242, "probe-user", "probe-pass", 1201)
                    fail("Should refuse external host")
                } catch (_: IllegalArgumentException) {
                    assertFalse(probe.isAuthenticated)
                }
            }
        }
    }

    @Test
    fun searchRequiresAuthenticationAndExplicitPositiveToken() {
        A0SoulseekLoopbackProbe().use { probe ->
            try {
                probe.search("test", 4)
                fail("Search without login should be rejected")
            } catch (_: IllegalStateException) { }
            try {
                probe.search("test", 0)
                fail("Search without a positive token should be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test(timeout = 12000)
    fun refusedLoginIsRedactedAndClosesConnection() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val closed = pool.submit<Boolean> {
                    server.accept().use { peer ->
                        peer.soTimeout = 4000
                        readFrame(peer.getInputStream())
                        peer.getOutputStream().write(
                            frame(1, byteArrayOf(0) + stringBytes("private-server-reason"))
                        )
                        peer.getOutputStream().flush()
                        peer.getInputStream().read() == -1
                    }
                }
                A0SoulseekLoopbackProbe().use { probe ->
                    try {
                        probe.connect("127.0.0.1", server.localPort, "probe-user", "probe-pass", 1201)
                        fail("Server rejected credentials")
                    } catch (e: A0SoulseekLoopbackProbe.LoginRejectedException) {
                        assertFalse(e.message.orEmpty().contains("private-server-reason"))
                        assertFalse(e.message.orEmpty().contains("probe-pass"))
                    }
                    assertFalse(probe.isAuthenticated)
                }
                assertTrue(closed.get(7, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test(timeout = 12000)
    fun oversizedServerFrameFailsClosedWithoutAllocatingPayload() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val closed = pool.submit<Boolean> {
                    server.accept().use { peer ->
                        peer.soTimeout = 4000
                        readFrame(peer.getInputStream())
                        peer.getOutputStream().write(intBytes(2_000_000))
                        peer.getOutputStream().flush()
                        peer.getInputStream().read() == -1
                    }
                }
                A0SoulseekLoopbackProbe().use { probe ->
                    try {
                        probe.connect("127.0.0.1", server.localPort, "probe-user", "probe-pass", 1201)
                        fail("Oversized server response should fail closed")
                    } catch (_: java.io.IOException) { }
                    assertFalse(probe.isAuthenticated)
                }
                assertTrue(closed.get(7, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte()
    )

    private fun stringBytes(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return intBytes(bytes.size) + bytes
    }

    private fun frame(code: Int, payload: ByteArray): ByteArray =
        intBytes(4 + payload.size) + intBytes(code) + payload

    private fun readInt(input: InputStream): Int {
        val bytes = ByteArray(4)
        var offset = 0
        while (offset < 4) {
            val amount = input.read(bytes, offset, 4 - offset)
            if (amount < 0) throw EOFException("Incomplete 4-byte field")
            offset += amount
        }
        return (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8) or
            ((bytes[2].toInt() and 255) shl 16) or ((bytes[3].toInt() and 255) shl 24)
    }

    private fun readString(input: InputStream): String {
        val length = readInt(input)
        require(length in 0..4096)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val amount = input.read(bytes, offset, length - offset)
            if (amount < 0) throw EOFException("Incomplete string")
            offset += amount
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readFrame(input: InputStream): ByteArray {
        val length = readInt(input)
        require(length in 4..4096)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val amount = input.read(bytes, offset, length - offset)
            if (amount < 0) throw EOFException("Incomplete frame")
            offset += amount
        }
        return bytes
    }
}
