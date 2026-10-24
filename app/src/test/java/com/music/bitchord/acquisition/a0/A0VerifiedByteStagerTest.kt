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
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A0 app-private staging contract exercised with an owner-generated WAV and local TCP.
 * These tests do NOT prove any Soulseek peer handshake or real P2P transfer.
 */
class A0VerifiedByteStagerTest {
    @get:Rule val directory = TemporaryFolder()

    @Test(timeout = 12000)
    fun stagesActualWavBytesFromLocalSocketOnlyAfterExactSha256Verification() {
        val ownedWav = generatedPcmWav()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val sender = executor.submit {
                    server.accept().use { peer ->
                        for (chunk in ownedWav.asList().chunked(137)) {
                            peer.getOutputStream().write(chunk.toByteArray())
                        }
                        peer.getOutputStream().flush()
                    }
                }
                Socket("127.0.0.1", server.localPort).use { receiver ->
                    receiver.soTimeout = 4000
                    val receipt = A0VerifiedByteStager.stage(
                        source = receiver.getInputStream(),
                        privateDirectory = directory.root,
                        expectedByteCount = ownedWav.size.toLong(),
                        expectedSha256Hex = sha256(ownedWav),
                    )
                    assertEquals(ownedWav.size.toLong(), receipt.byteCount)
                    assertEquals(sha256(ownedWav), receipt.sha256Hex)
                    assertEquals("WAV_HEADER_ONLY", receipt.containerHint)
                    assertArrayEquals(ownedWav, receipt.file.readBytes())
                    assertTrue(receipt.file.canonicalPath.startsWith(directory.root.canonicalPath + "/"))
                    assertTrue(receipt.file.name.endsWith(".verified"))
                    assertFalse(directory.root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
                }
                sender.get(7, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun incompleteTransferLeavesNoStagedOrPartialFiles() {
        val ownedWav = generatedPcmWav()
        assertIOException {
            A0VerifiedByteStager.stage(
                ByteArrayInputStream(ownedWav.copyOfRange(0, 50)),
                directory.root,
                ownedWav.size.toLong(),
                sha256(ownedWav),
            )
        }
        assertTrue(directory.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun checksumMismatchFailsClosedAndDeletesTempData() {
        val ownedWav = generatedPcmWav()
        assertIOException {
            A0VerifiedByteStager.stage(
                ByteArrayInputStream(ownedWav),
                directory.root,
                ownedWav.size.toLong(),
                "0".repeat(64),
            )
        }
        assertTrue(directory.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun unknownContainerAndMisleadingFilenameDoNotProducePublication() {
        val fake = ByteArray(4096) { 'x'.code.toByte() }
        assertIOException {
            A0VerifiedByteStager.stage(
                ByteArrayInputStream(fake),
                directory.root,
                fake.size.toLong(),
                sha256(fake),
            )
        }
        assertTrue(directory.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun cancellationAfterFirstChunkDeletesPartialAndDoesNotCreateVerifiedResult() {
        val cancelled = AtomicBoolean(false)
        val ownedWav = generatedPcmWav(32768)
        assertIOException {
            A0VerifiedByteStager.stage(
                source = ByteArrayInputStream(ownedWav),
                privateDirectory = directory.root,
                expectedByteCount = ownedWav.size.toLong(),
                expectedSha256Hex = sha256(ownedWav),
                isCancelled = { cancelled.get() },
                onProgress = { transferred -> if (transferred > 0) cancelled.set(true) },
            )
        }
        assertTrue(cancelled.get())
        assertTrue(directory.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun refusesUnboundedSizesAndUntrustedHashBeforeCreatingFiles() {
        val bytes = generatedPcmWav()
        try {
            A0VerifiedByteStager.stage(ByteArrayInputStream(bytes), directory.root, Long.MAX_VALUE, sha256(bytes))
            fail("Unbounded byte count should be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            A0VerifiedByteStager.stage(ByteArrayInputStream(bytes), directory.root, bytes.size.toLong(), "not-a-hash")
            fail("Untrusted digest should be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(directory.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun identicalDownloadsNeverOverwritePreviouslyVerifiedFile() {
        val audio = generatedPcmWav()
        val a = A0VerifiedByteStager.stage(
            ByteArrayInputStream(audio), directory.root, audio.size.toLong(), sha256(audio)
        )
        val b = A0VerifiedByteStager.stage(
            ByteArrayInputStream(audio), directory.root, audio.size.toLong(), sha256(audio)
        )
        assertFalse(a.file.absolutePath == b.file.absolutePath)
        assertArrayEquals(audio, a.file.readBytes())
        assertArrayEquals(audio, b.file.readBytes())
    }

    private fun generatedPcmWav(sampleCount: Int = 512): ByteArray {
        val pcm = ByteArray(sampleCount * 2) { 0 }
        val header = ByteArray(44)
        fun writeI32(offset: Int, value: Int) {
            for (i in 0..3) header[offset + i] = (value ushr (8 * i)).toByte()
        }
        fun writeI16(offset: Int, value: Int) {
            for (i in 0..1) header[offset + i] = (value ushr (8 * i)).toByte()
        }
        fun marker(offset: Int, value: String) = value.toByteArray(Charsets.US_ASCII)
            .copyInto(header, offset)
        marker(0, "RIFF")
        writeI32(4, header.size + pcm.size - 8)
        marker(8, "WAVE")
        marker(12, "fmt ")
        writeI32(16, 16)
        writeI16(20, 1)
        writeI16(22, 1)
        writeI32(24, 8000)
        writeI32(28, 16000)
        writeI16(32, 2)
        writeI16(34, 16)
        marker(36, "data")
        writeI32(40, pcm.size)
        return header + pcm
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun assertIOException(block: () -> Unit) {
        try {
            block()
            fail("Staging malformed, cancelled or incomplete bytes must fail")
        } catch (_: IOException) { }
    }
}
