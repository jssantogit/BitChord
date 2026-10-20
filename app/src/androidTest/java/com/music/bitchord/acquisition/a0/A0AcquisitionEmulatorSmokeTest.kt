package com.music.bitchord.acquisition.a0

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Instrumented A0 smoke runs INSIDE an installed Android dev APK.
 * TCP endpoint is literally 127.0.0.1; owned PCM WAV fixture; no live Soulseek network.
 */
@RunWith(AndroidJUnit4::class)
class A0AcquisitionEmulatorSmokeTest {
    @Test
    fun installedApkHasExpectedRuntimeAndPrivateStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.dev.bitchord", context.packageName)
        assertTrue(Build.VERSION.SDK_INT >= 26)
        assertEquals(36, context.applicationInfo.targetSdkVersion)
        val directory = File(context.filesDir, "a0-smoke-" + System.nanoTime())
        assertTrue(directory.mkdirs())
        try {
            assertTrue(directory.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            assertTrue(directory.canWrite())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 20000)
    fun onDeviceLoginAndSearchUseLocalTcpAndCloseSocket() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val task = pool.submit<Boolean> {
                    listener.accept().use { peer ->
                        peer.soTimeout = 4000
                        val login = ByteArrayInputStream(readFrame(peer.getInputStream()))
                        assertEquals(1, readInt(login))
                        assertEquals("android-fixture", readString(login))
                        assertEquals("synthetic-only-pass", readString(login))
                        assertEquals(170, readInt(login))
                        readString(login) // historical protocol MD5; never log fixture password.
                        assertEquals(1201, readInt(login))
                        peer.getOutputStream().write(frame(1, byteArrayOf(1) +
                            text("ok") + byteArrayOf(127, 0, 0, 1) + text("hash") + byteArrayOf(0)))
                        peer.getOutputStream().flush()
                        val search = ByteArrayInputStream(readFrame(peer.getInputStream()))
                        assertEquals(26, readInt(search))
                        assertEquals(17, readInt(search))
                        assertEquals("test owned wav", readString(search))
                        peer.getInputStream().read() == -1
                    }
                }
                A0SoulseekLoopbackProbe().use { client ->
                    client.connect("127.0.0.1", listener.localPort, "android-fixture", "synthetic-only-pass", 1201)
                    assertTrue(client.isAuthenticated)
                    client.search("test owned wav", 17)
                }
                assertTrue(task.get(10, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test(timeout = 20000)
    fun localTcpWavIsVerifiedInRealAndroidAppPrivateStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "a0-smoke-" + System.nanoTime())
        assertTrue(directory.mkdirs())
        val bytes = ownedWav()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                val pool = Executors.newSingleThreadExecutor()
                try {
                    val sender = pool.submit {
                        listener.accept().use { peer ->
                            peer.getOutputStream().write(bytes.copyOfRange(0, 13))
                            peer.getOutputStream().write(bytes.copyOfRange(13, bytes.size))
                            peer.getOutputStream().flush()
                        }
                    }
                    Socket("127.0.0.1", listener.localPort).use { socket ->
                        socket.soTimeout = 4000
                        val result = A0VerifiedByteStager.stage(
                            socket.getInputStream(), directory, bytes.size.toLong(), sha256(bytes)
                        )
                        assertEquals(bytes.size.toLong(), result.byteCount)
                        assertEquals(sha256(bytes), result.sha256Hex)
                        assertEquals("WAV_HEADER_ONLY", result.containerHint)
                        assertArrayEquals(bytes, result.file.readBytes())
                        assertTrue(result.file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                        assertTrue(result.file.name.endsWith(".verified"))
                        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
                    }
                    sender.get(10, TimeUnit.SECONDS)
                } finally {
                    pool.shutdownNow()
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 10000)
    fun corruptedLocalBytesAreDiscardedInsideInstalledApp() {
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "a0-smoke-" + System.nanoTime()
        )
        assertTrue(directory.mkdirs())
        try {
            val bytes = ownedWav()
            try {
                A0VerifiedByteStager.stage(ByteArrayInputStream(bytes), directory, bytes.size.toLong(), "0".repeat(64))
                fail("Invalid digest must never create a verified file")
            } catch (_: IOException) {
                assertTrue(directory.listFiles().isNullOrEmpty())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 20000)
    fun androidPeerMessageHandshakeAndDecisionAreLoopbackOnly() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val task = pool.submit<Boolean> {
                    listener.accept().use { peer ->
                        peer.soTimeout = 4000
                        val init = ByteArrayInputStream(readFrame(peer.getInputStream()))
                        assertEquals(1, init.read())
                        assertEquals("android-fixture", readString(init))
                        assertEquals("P", readString(init))
                        assertEquals(55, readInt(init))
                        val req = ByteArrayInputStream(readFrame(peer.getInputStream()))
                        assertEquals(40, readInt(req))
                        assertEquals(0, readInt(req))
                        assertEquals(57, readInt(req))
                        assertEquals("Owned\\test.wav", readString(req))
                        assertEquals(300L, readLong(req))
                        peer.getOutputStream().write(frame(41, intLE(57) + byteArrayOf(1) + longLE(300L)))
                        peer.getOutputStream().flush()
                        true
                    }
                }
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    socket.soTimeout = 4000
                    socket.getOutputStream().write(A0SoulseekPeerWireProbe.peerInit("android-fixture", 55))
                    socket.getOutputStream().write(A0SoulseekPeerWireProbe.downloadRequest("Owned\\test.wav", 57, 300L))
                    socket.getOutputStream().flush()
                    val response = A0SoulseekPeerWireProbe.readTransferDecision(
                        socket.getInputStream(), 57, 300L
                    )
                    assertTrue(response.allowed)
                    assertEquals(300L, response.peerReportedByteCount)
                }
                assertTrue(task.get(10, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    private fun ownedWav(): ByteArray {
        val header = ByteArray(44)
        fun mark(at: Int, text: String) = text.toByteArray(Charsets.US_ASCII).copyInto(header, at)
        fun i32(at: Int, n: Int) { repeat(4) { header[at + it] = (n ushr (it * 8)).toByte() } }
        fun i16(at: Int, n: Int) { repeat(2) { header[at + it] = (n ushr (it * 8)).toByte() } }
        mark(0, "RIFF"); i32(4, 292); mark(8, "WAVE"); mark(12, "fmt ")
        i32(16, 16); i16(20, 1); i16(22, 1)
        i32(24, 8000); i32(28, 16000); i16(32, 2); i16(34, 16)
        mark(36, "data"); i32(40, 256)
        return header + ByteArray(256)
    }
    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun intLE(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
    private fun longLE(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
    private fun text(s: String): ByteArray {
        val data = s.toByteArray(Charsets.UTF_8)
        return intLE(data.size) + data
    }
    private fun frame(code: Int, payload: ByteArray): ByteArray = intLE(4 + payload.size) + intLE(code) + payload
    private fun readInt(input: InputStream): Int {
        val x = exactly(input, 4)
        return (x[0].toInt() and 255) or ((x[1].toInt() and 255) shl 8) or
            ((x[2].toInt() and 255) shl 16) or ((x[3].toInt() and 255) shl 24)
    }
    private fun readLong(input: InputStream): Long {
        val x = exactly(input, 8)
        return x.indices.fold(0L) { total, i -> total or ((x[i].toLong() and 255L) shl (i * 8)) }
    }
    private fun readString(input: InputStream): String {
        val length = readInt(input)
        require(length in 0..2048)
        return String(exactly(input, length), Charsets.UTF_8)
    }
    private fun readFrame(input: InputStream): ByteArray {
        val length = readInt(input)
        require(length in 4..4096)
        return exactly(input, length)
    }
    private fun exactly(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val amount = input.read(bytes, offset, count - offset)
            if (amount <= 0) throw IOException("Incomplete localhost fixture")
            offset += amount
        }
        return bytes
    }
}
