package com.music.bitchord.acquisition.a0

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest

/**
 * A0 modern peer-message exchange and file stream, after external peer identity
 * verification. Local in-memory streams only; this does not authorize a live peer.
 */
class A0ModernIncomingTransferTest {
    @get:Rule val files = TemporaryFolder()
    private val filename = "CC0\\ReferenceTone.wav"
    private val wav = ownWav()
    private val digest = MessageDigest.getInstance("SHA-256").digest(wav)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test
    fun acceptsOnlyExpectedUploaderTokenAndSizeThenSendsRawZeroOffsetBeforeFile() {
        val pReply = ByteArrayOutputStream()
        val ticket = A0ModernIncomingTransfer.acceptUpload(
            ByteArrayInputStream(uploadRequest(104, filename, wav.size.toLong())),
            pReply, filename, wav.size.toLong(), digest
        )
        val response = ByteArrayInputStream(pReply.toByteArray())
        assertEquals(9, i32(response))
        assertEquals(41, i32(response))
        assertEquals(104, i32(response))
        assertEquals(1, response.read())
        assertEquals(-1, response.read())

        val fOut = ByteArrayOutputStream()
        val receipt = A0ModernIncomingTransfer.receiveAfterFConnection(
            ByteArrayInputStream(le32(104) + wav),
            fOut, ticket, files.root
        )
        assertArrayEquals(ByteArray(8), fOut.toByteArray())
        assertArrayEquals(wav, receipt.file.readBytes())
        assertEquals(wav.size.toLong(), receipt.byteCount)
        assertEquals("WAV_HEADER_ONLY", receipt.containerHint)
        assertEquals(1, files.root.listFiles().orEmpty().size)
        assertTrue(receipt.file.name.endsWith(".verified"))
    }

    @Test
    fun wrongTokenMustFailBeforeSendingOffsetOrCreatingAnyFile() {
        val ticket = accept(uploadRequest(104, filename, wav.size.toLong()))
        val response = ByteArrayOutputStream()
        expectIOException {
            A0ModernIncomingTransfer.receiveAfterFConnection(
                ByteArrayInputStream(le32(105) + wav), response, ticket, files.root
            )
        }
        assertEquals(0, response.size())
        assertTrue(files.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun disconnectedDataStreamMustDeletePartialBytes() {
        val ticket = accept(uploadRequest(104, filename, wav.size.toLong()))
        val response = ByteArrayOutputStream()
        expectIOException {
            A0ModernIncomingTransfer.receiveAfterFConnection(
                ByteArrayInputStream(le32(104) + wav.copyOfRange(0, 73)),
                response, ticket, files.root
            )
        }
        assertArrayEquals(ByteArray(8), response.toByteArray())
        assertTrue(files.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun digestMismatchMustDeletePartialWithoutVerifiedFile() {
        val ticket = A0ModernIncomingTransfer.acceptUpload(
            ByteArrayInputStream(uploadRequest(104, filename, wav.size.toLong())),
            ByteArrayOutputStream(), filename, wav.size.toLong(), "0".repeat(64)
        )
        expectIOException {
            A0ModernIncomingTransfer.receiveAfterFConnection(
                ByteArrayInputStream(le32(104) + wav), ByteArrayOutputStream(), ticket, files.root
            )
        }
        assertTrue(files.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun unsolicitedFilenameSizeOrDirectionCannotBeAuthorized() {
        val attempts = listOf(
            uploadRequest(104, "Private\\Another.wav", wav.size.toLong()),
            uploadRequest(104, filename, wav.size.toLong() + 1),
            uploadRequest(104, filename, wav.size.toLong(), direction = 0),
            uploadRequest(0, filename, wav.size.toLong()),
            uploadRequest(104, "..\\ReferenceTone.wav", wav.size.toLong()),
        )
        for (request in attempts) {
            val pReply = ByteArrayOutputStream()
            expectIOException {
                A0ModernIncomingTransfer.acceptUpload(
                    ByteArrayInputStream(request), pReply, filename, wav.size.toLong(), digest
                )
            }
            assertEquals(0, pReply.size())
        }
        assertTrue(files.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun incompleteAndExcessiveFramesAreRejectedWithoutResponding() {
        val valid = uploadRequest(104, filename, wav.size.toLong())
        for (bad in listOf(
            valid.copyOf(valid.size - 3),
            le32(999999) + valid,
            le32(3) + byteArrayOf(1, 2, 3),
        )) {
            val response = ByteArrayOutputStream()
            expectIOException {
                A0ModernIncomingTransfer.acceptUpload(
                    ByteArrayInputStream(bad), response, filename, wav.size.toLong(), digest
                )
            }
            assertEquals(0, response.size())
        }
    }

    @Test
    fun missingTrustedDigestMustBeRejectedBeforeNetworkRead() {
        try {
            A0ModernIncomingTransfer.acceptUpload(
                ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(),
                filename, wav.size.toLong(), "not-a-digest"
            )
            fail("Expected input validation")
        } catch (_: IllegalArgumentException) {}
    }

    private fun accept(payload: ByteArray) = A0ModernIncomingTransfer.acceptUpload(
        ByteArrayInputStream(payload), ByteArrayOutputStream(),
        filename, wav.size.toLong(), digest
    )

    private fun uploadRequest(token: Int, name: String, size: Long, direction: Int = 1): ByteArray {
        val path = name.toByteArray(Charsets.UTF_8)
        val frame = le32(40) + le32(direction) + le32(token) + le32(path.size) + path +
            if (direction == 1) le64(size) else byteArrayOf()
        return le32(frame.size) + frame
    }

    private fun i32(input: ByteArrayInputStream): Int {
        val b = ByteArray(4)
        if (input.read(b) != 4) throw EOFException()
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun le32(v: Int) = ByteArray(4) { (v ushr (it * 8)).toByte() }
    private fun le64(v: Long) = ByteArray(8) { (v ushr (it * 8)).toByte() }
    private fun expectIOException(action: () -> Unit) {
        try { action(); fail("Expected modern A0 protocol rejection") } catch (_: IOException) {}
    }
    private fun ownWav(): ByteArray {
        val h = ByteArray(44)
        fun word(i: Int, v: Int) { h[i] = v.toByte(); h[i + 1] = (v ushr 8).toByte() }
        fun dword(i: Int, v: Int) { repeat(4) { h[i + it] = (v ushr (8 * it)).toByte() } }
        fun ascii(i: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(h, i) }
        ascii(0, "RIFF"); dword(4, 36 + 160); ascii(8, "WAVE")
        ascii(12, "fmt "); dword(16, 16); word(20, 1); word(22, 1)
        dword(24, 8000); dword(28, 16000); word(32, 2); word(34, 16)
        ascii(36, "data"); dword(40, 160)
        return h + ByteArray(160) { 0 }
    }
    @Test fun observedPWireAcceptsLargeBoundedTransferWithoutAllocatingItsBytes() {
        val size=96L*1024L*1024L
        val response=ByteArrayOutputStream()
        A0ModernIncomingTransfer.acceptObservedUpload(
            ByteArrayInputStream(uploadRequest(109,filename,size)),
            response,filename,size
        )
        assertEquals(13,response.size()) // four-byte frame length + 9-byte response
        try {
            A0ModernIncomingTransfer.acceptObservedUpload(
                ByteArrayInputStream(ByteArray(0)),ByteArrayOutputStream(),
                filename,256L*1024L*1024L+1L
            )
            fail("Bounded observed transfer must reject oversized bytes before network I/O")
        } catch (_:IllegalArgumentException) {}
    }

}
