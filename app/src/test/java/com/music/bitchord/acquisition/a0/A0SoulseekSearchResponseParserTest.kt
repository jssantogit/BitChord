package com.music.bitchord.acquisition.a0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream

/**
 * A0 search-results parsing tests use deterministic zlib protocol frames.
 * They are NOT observations from real Soulseek peers.
 */
class A0SoulseekSearchResponseParserTest {
    @Test
    fun decodesAvailableAndLockedCandidatesWithoutCreatingCanonicalIdentity() {
        val frame = responseFrame(
            token = 420,
            file = fileField(
                "Artist\\album\\authorized track.flac", 4096L, "flac",
                mapOf(0 to 1411, 1 to 230, 4 to 44100),
            ),
            lockedFile = fileField("private\\locked.mp3", 9000L, "mp3", emptyMap()),
        )
        val result = A0SoulseekSearchResponseParser.parse(frame, expectedToken = 420)

        assertEquals("fixture-peer", result.peerUsername)
        assertEquals(420, result.token)
        assertTrue(result.hasFreeUploadSlot)
        assertEquals(128_000, result.uploadSpeed)
        assertEquals(2, result.queueLength)
        assertEquals(1, result.files.size)
        assertEquals("Artist\\album\\authorized track.flac", result.files.single().remoteFilename)
        assertEquals(4096L, result.files.single().sizeBytes)
        assertEquals("flac", result.files.single().extension)
        assertEquals(1411, result.files.single().attributes[0])
        assertEquals(1, result.lockedFiles.size)
        assertEquals("private\\locked.mp3", result.lockedFiles.single().remoteFilename)
        // A search response is weak peer evidence, not an authenticated RecordingId.
        assertFalse(result.files.single().remoteFilename.contains("RecordingId"))
    }

    @Test
    fun rejectsUnsolicitedTokenAndUnexpectedPeerMessageCode() {
        val valid = responseFrame(token = 42)
        assertRejected { A0SoulseekSearchResponseParser.parse(valid, expectedToken = 777) }
        val wrongCode = valid.copyOf().also { it[4] = 40 }
        assertRejected { A0SoulseekSearchResponseParser.parse(wrongCode, expectedToken = 42) }
        assertRejected { A0SoulseekSearchResponseParser.parse(valid, expectedToken = 0) }
    }

    @Test
    fun rejectsTruncatedAndInconsistentFrames() {
        val valid = responseFrame(token = 42)
        assertRejected { A0SoulseekSearchResponseParser.parse(valid.copyOf(valid.size - 2), 42) }
        val oversizedHeader = valid.copyOf().also {
            val value = 2_000_000
            for (i in 0..3) it[i] = (value ushr (8 * i)).toByte()
        }
        assertRejected { A0SoulseekSearchResponseParser.parse(oversizedHeader, 42) }
    }

    @Test
    fun rejectsDecompressionBombAndUnboundedResultCount() {
        val bomb = ByteArray(600_000) { 'x'.code.toByte() }
        assertRejected { A0SoulseekSearchResponseParser.parse(compressedFrame(bomb), 42) }

        val countOverLimit = ByteArrayOutputStream().apply {
            write(str("fixture-peer"))
            write(i32(42))
            write(i32(10_000))
        }.toByteArray()
        assertRejected { A0SoulseekSearchResponseParser.parse(compressedFrame(countOverLimit), 42) }
    }

    @Test
    fun rejectsNegativeFileSizeAndMalformedStringLength() {
        val negative = responseFrame(token = 42, file = fileField("wrong.flac", -1L, "flac", emptyMap()))
        assertRejected { A0SoulseekSearchResponseParser.parse(negative, 42) }

        val badUsernameLength = ByteArrayOutputStream().apply {
            write(i32(-1))
            write(i32(42))
        }.toByteArray()
        assertRejected { A0SoulseekSearchResponseParser.parse(compressedFrame(badUsernameLength), 42) }
    }

    @Test
    fun acceptsLegacyLatin1FilenameWithoutSilentlyChangingByteIdentity() {
        val latin1Filename = byteArrayOf(0x63, 0x61, 0x66, 0xe9.toByte(), 0x2e, 0x6d, 0x70, 0x33)
        val file = ByteArrayOutputStream().apply {
            write(1)
            write(i32(latin1Filename.size))
            write(latin1Filename)
            write(i64(8192))
            write(str("mp3"))
            write(i32(0))
        }.toByteArray()
        val result = A0SoulseekSearchResponseParser.parse(responseFrame(42, file = file), 42)
        assertEquals("café.mp3", result.files.single().remoteFilename)
    }

    private fun responseFrame(
        token: Int,
        file: ByteArray = fileField("fixture.wav", 4096, "wav", emptyMap()),
        lockedFile: ByteArray? = null,
    ): ByteArray {
        val decoded = ByteArrayOutputStream().apply {
            write(str("fixture-peer"))
            write(i32(token))
            write(i32(1))
            write(file)
            write(1)
            write(i32(128_000))
            write(i32(2))
            write(i32(0)) // optional reserved integer
            write(i32(if (lockedFile == null) 0 else 1))
            if (lockedFile != null) write(lockedFile)
        }.toByteArray()
        return compressedFrame(decoded)
    }

    private fun fileField(name: String, size: Long, ext: String, attrs: Map<Int, Int>): ByteArray =
        ByteArrayOutputStream().apply {
            write(1) // file code
            write(str(name))
            write(i64(size))
            write(str(ext))
            write(i32(attrs.size))
            attrs.forEach { (type, value) ->
                write(i32(type))
                write(i32(value))
            }
        }.toByteArray()

    private fun compressedFrame(data: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write(data) }
        }.toByteArray()
        val length = 4 + compressed.size
        return i32(length) + i32(9) + compressed // Peer.SearchResponse = 9
    }

    private fun str(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return i32(bytes.size) + bytes
    }
    private fun i32(n: Int): ByteArray =
        ByteArray(4) { i -> (n ushr (i * 8)).toByte() }
    private fun i64(n: Long): ByteArray =
        ByteArray(8) { i -> (n ushr (i * 8)).toByte() }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Malformed or unsolicited message should have been rejected")
        } catch (_: IOException) {
            // Expected: parser must never expose untrusted peer fields in failure text.
        } catch (_: IllegalArgumentException) {
            // Expected: invalid API invocation.
        }
    }
}
