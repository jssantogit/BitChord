package com.music.bitchord.acquisition.a0

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Full local P-then-F state gate. Tests use owner-created WAV, never real Soulseek.
 * A rejected/ambiguous P response MUST NOT open the F socket.
 */
class A0LocalTwoChannelSessionTest {
    @get:Rule val files = TemporaryFolder()

    @Test(timeout = 15000)
    fun successfulPDecisionOpensFAndCommitsExactOwnerCreatedAudio() {
        val audio = ownedWav()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { message ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { file ->
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val p = pool.submit<Boolean> {
                        message.accept().use { peer ->
                            peer.soTimeout = 4000
                            assertPRequest(peer.getInputStream(), audio.size.toLong())
                            peer.getOutputStream().write(response(103, true, audio.size.toLong()))
                            peer.getOutputStream().flush()
                            true
                        }
                    }
                    val f = pool.submit<Boolean> {
                        file.accept().use { peer ->
                            peer.soTimeout = 4000
                            assertFInit(peer.getInputStream())
                            peer.getOutputStream().write(audio.copyOfRange(0, 17))
                            peer.getOutputStream().write(audio.copyOfRange(17, audio.size))
                            peer.getOutputStream().flush()
                            true
                        }
                    }
                    val outcome = A0LocalTwoChannelSession.receiveOwnedFixture(
                        messagePort = message.localPort,
                        filePort = file.localPort,
                        username = "test-owner",
                        messageConnectionToken = 101,
                        fileConnectionToken = 107,
                        transferToken = 103,
                        remoteFilename = "Owned\\synthetic.wav",
                        expectedBytes = audio.size.toLong(),
                        trustedSha256Hex = sha256(audio),
                        appPrivateDirectory = files.root
                    )
                    assertTrue(p.get(8, TimeUnit.SECONDS))
                    assertTrue(f.get(8, TimeUnit.SECONDS))
                    assertTrue(outcome is A0LocalTwoChannelSession.Outcome.Completed)
                    val receipt = (outcome as A0LocalTwoChannelSession.Outcome.Completed).receipt
                    assertArrayEquals(audio, receipt.file.readBytes())
                    assertEquals(audio.size.toLong(), receipt.byteCount)
                    assertTrue(receipt.file.name.endsWith(".verified"))
                    assertFalse(files.root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
                } finally {
                    pool.shutdownNow()
                }
            }
        }
    }

    @Test(timeout = 15000)
    fun deniedPDecisionMustNeverOpenFOrStageAnything() {
        val audio = ownedWav()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { message ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { file ->
                file.soTimeout = 600
                val pool = Executors.newSingleThreadExecutor()
                try {
                    val p = pool.submit<Boolean> {
                        message.accept().use { peer ->
                            assertPRequest(peer.getInputStream(), audio.size.toLong())
                            peer.getOutputStream().write(response(103, false))
                            peer.getOutputStream().flush()
                            true
                        }
                    }
                    val result = receive(message.localPort, file.localPort, audio)
                    assertTrue(result is A0LocalTwoChannelSession.Outcome.Denied)
                    assertTrue(p.get(8, TimeUnit.SECONDS))
                    assertNoFileConnection(file)
                    assertTrue(files.root.listFiles().isNullOrEmpty())
                } finally {
                    pool.shutdownNow()
                }
            }
        }
    }

    @Test(timeout = 15000)
    fun ambiguousAllowedSizeMustNotStartFileTransfer() {
        val audio = ownedWav()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { message ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { file ->
                file.soTimeout = 600
                val pool = Executors.newSingleThreadExecutor()
                try {
                    val p = pool.submit<Boolean> {
                        message.accept().use { peer ->
                            assertPRequest(peer.getInputStream(), audio.size.toLong())
                            // Allowed but no peer-reported size: fail closed, do not trust guessed bytes.
                            peer.getOutputStream().write(response(103, true, null))
                            peer.getOutputStream().flush()
                            true
                        }
                    }
                    assertIOException { receive(message.localPort, file.localPort, audio) }
                    assertTrue(p.get(8, TimeUnit.SECONDS))
                    assertNoFileConnection(file)
                    assertTrue(files.root.listFiles().isNullOrEmpty())
                } finally {
                    pool.shutdownNow()
                }
            }
        }
    }

    @Test(timeout = 15000)
    fun truncatedFileAfterAllowedPDecisionLeavesNoPartialOrVerifiedFile() {
        val audio = ownedWav()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { message ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { file ->
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val p = pool.submit<Boolean> {
                        message.accept().use { peer ->
                            assertPRequest(peer.getInputStream(), audio.size.toLong())
                            peer.getOutputStream().write(response(103, true, audio.size.toLong()))
                            peer.getOutputStream().flush()
                            true
                        }
                    }
                    val f = pool.submit<Boolean> {
                        file.accept().use { peer ->
                            assertFInit(peer.getInputStream())
                            peer.getOutputStream().write(audio.copyOfRange(0, 64))
                            peer.getOutputStream().flush()
                            true
                        }
                    }
                    assertIOException { receive(message.localPort, file.localPort, audio) }
                    assertTrue(p.get(8, TimeUnit.SECONDS))
                    assertTrue(f.get(8, TimeUnit.SECONDS))
                    assertTrue(files.root.listFiles().isNullOrEmpty())
                } finally {
                    pool.shutdownNow()
                }
            }
        }
    }

    @Test
    fun portAndDigestInputsAreRejectedBeforeAnyNetworkActivity() {
        val audio = ownedWav()
        assertIllegal {
            A0LocalTwoChannelSession.receiveOwnedFixture(
                0, 1234, "test-owner", 101, 107, 103, "x.wav",
                audio.size.toLong(), sha256(audio), files.root
            )
        }
        assertIllegal {
            A0LocalTwoChannelSession.receiveOwnedFixture(
                1234, 1234, "test-owner", 101, 107, 103, "x.wav",
                audio.size.toLong(), sha256(audio), files.root
            )
        }
        assertIllegal {
            A0LocalTwoChannelSession.receiveOwnedFixture(
                1111, 2222, "test-owner", 101, 107, 103, "x.wav",
                audio.size.toLong(), "not-trusted", files.root
            )
        }
        assertTrue(files.root.listFiles().isNullOrEmpty())
    }

    private fun receive(p: Int, f: Int, audio: ByteArray) =
        A0LocalTwoChannelSession.receiveOwnedFixture(
            p, f, "test-owner", 101, 107, 103, "Owned\\synthetic.wav",
            audio.size.toLong(), sha256(audio), files.root
        )

    private fun assertPRequest(stream: InputStream, size: Long) {
        val init = ByteArrayInputStream(readFrame(stream))
        assertEquals(1, init.read())
        assertEquals("test-owner", readText(init))
        assertEquals("P", readText(init))
        assertEquals(101, readInt(init))
        val request = ByteArrayInputStream(readFrame(stream))
        assertEquals(40, readInt(request))
        assertEquals(0, readInt(request))
        assertEquals(103, readInt(request))
        assertEquals("Owned\\synthetic.wav", readText(request))
        assertEquals(size, readLong(request))
    }
    private fun assertFInit(stream: InputStream) {
        val init = ByteArrayInputStream(readFrame(stream))
        assertEquals(1, init.read())
        assertEquals("test-owner", readText(init))
        assertEquals("F", readText(init))
        assertEquals(107, readInt(init))
        assertEquals(103, readInt(stream)) // Raw 4-byte transfer token, no length frame.
    }
    private fun assertNoFileConnection(file: ServerSocket) {
        try {
            file.accept().close()
            fail("F socket must not open following denied/ambiguous P")
        } catch (_: SocketTimeoutException) {
            // Expected: no F connection initiated.
        }
    }

    private fun response(token: Int, accepted: Boolean, count: Long? = null): ByteArray {
        val bytes = i32(41) + i32(token) + byteArrayOf(if (accepted) 1 else 0) +
            (if (!accepted) text("denied") else count?.let { i64(it) } ?: byteArrayOf())
        return i32(bytes.size) + bytes
    }
    private fun readFrame(stream: InputStream): ByteArray {
        val size = readInt(stream)
        require(size in 4..4096)
        return readExactly(stream, size)
    }
    private fun readText(stream: InputStream): String {
        val size = readInt(stream)
        require(size in 0..1024)
        return String(readExactly(stream, size), Charsets.UTF_8)
    }
    private fun readInt(stream: InputStream): Int {
        val b = readExactly(stream, 4)
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun readLong(stream: InputStream): Long =
        readExactly(stream, 8).foldIndexed(0L) { i, acc, x -> acc or ((x.toLong() and 255L) shl (i * 8)) }
    private fun readExactly(stream: InputStream, count: Int): ByteArray {
        val data = ByteArray(count)
        var p = 0
        while (p < count) {
            val n = stream.read(data, p, count - p)
            if (n <= 0) throw EOFException("incomplete owned fixture")
            p += n
        }
        return data
    }
    private fun ownedWav(): ByteArray {
        val header = ByteArray(44)
        fun marker(index: Int, text: String) = text.toByteArray(Charsets.US_ASCII).copyInto(header, index)
        fun int32(index: Int, value: Int) { repeat(4) { header[index + it] = (value ushr (it * 8)).toByte() } }
        fun int16(index: Int, value: Int) { repeat(2) { header[index + it] = (value ushr (it * 8)).toByte() } }
        marker(0, "RIFF"); int32(4, 292); marker(8, "WAVE")
        marker(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1)
        int32(24, 8000); int32(28, 16000); int16(32, 2); int16(34, 16)
        marker(36, "data"); int32(40, 256)
        return header + ByteArray(256)
    }
    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun i32(n: Int): ByteArray = ByteArray(4) { (n ushr (it * 8)).toByte() }
    private fun i64(n: Long): ByteArray = ByteArray(8) { (n ushr (it * 8)).toByte() }
    private fun text(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return i32(b.size) + b
    }
    private fun assertIOException(block: () -> Unit) {
        try { block(); fail("Expected unsafe A0 session to fail closed") } catch (_: IOException) {}
    }
    private fun assertIllegal(block: () -> Unit) {
        try { block(); fail("Expected invalid parameters rejected") } catch (_: IllegalArgumentException) {}
    }
}
