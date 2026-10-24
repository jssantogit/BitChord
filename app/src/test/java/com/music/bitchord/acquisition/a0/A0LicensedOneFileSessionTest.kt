package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Fail-closed, stream-only modern P/QueueUpload/F orchestration. NO external peer. */
class A0LicensedOneFileSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val wav = wavFixture()
    private val hash = MessageDigest.getInstance("SHA-256").digest(wav)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val remote = "Open\\OwnedTone.wav"

    @Test fun licensedPlanQueuesOnceThenAcceptsModernPAndVerifiesIncomingF() {
        val ready = A0LicensedOneFileSession.plan(reference(true), listing())
        assertTrue(ready is A0LicensedOneFileSession.Plan.Ready)
        val session = (ready as A0LicensedOneFileSession.Plan.Ready).session

        val queue = ByteArrayOutputStream()
        session.queueUpload(queue)
        assertArrayEquals(
            frame(43, string(remote)), queue.toByteArray()
        )

        val accept = ByteArrayOutputStream()
        session.acceptUpload(
            ByteArrayInputStream(transferRequest(73, remote, wav.size.toLong())),
            accept
        )
        assertArrayEquals(frame(41, le32(73) + byteArrayOf(1)), accept.toByteArray())

        val offset = ByteArrayOutputStream()
        val receipt = session.receiveFile(
            ByteArrayInputStream(le32(73) + wav),
            offset, temp.root
        )
        assertArrayEquals(ByteArray(8), offset.toByteArray())
        assertArrayEquals(wav, receipt.file.readBytes())
        assertEquals(wav.size.toLong(), receipt.byteCount)
        assertEquals(hash, receipt.sha256Hex)
        assertFalse(temp.root.listFiles().orEmpty().any { it.name.endsWith(".partial") })

        // Single-use: no replay, no second P/F session.
        reject { session.queueUpload(ByteArrayOutputStream()) }
        reject { session.receiveFile(ByteArrayInputStream(le32(73) + wav), ByteArrayOutputStream(), temp.root) }
    }

    @Test fun missingReviewMissingHashAndUnrelatedListingNeverQueue() {
        val unsafe = listOf(
            reference(false) to listing(),
            reference(true).copy(independentlyKnownSha256 = "") to listing(),
            reference(true) to listing().copy(remoteFilename = "Other\\CopyrightedSong.mp3"),
            reference(true) to listing().copy(locked = true),
            reference(true) to listing().copy(reportedSize = 44444),
        )
        for ((reference, listing) in unsafe) {
            val result = A0LicensedOneFileSession.plan(reference, listing)
            assertTrue(result is A0LicensedOneFileSession.Plan.Blocked)
        }
        assertTrue(temp.root.listFiles().isNullOrEmpty())
    }

    @Test fun uploadMustNotBeAcceptedBeforeQueueAndWrongTransferBurnsTicket() {
        val session = ready()
        val untouched = ByteArrayOutputStream()
        reject {
            session.acceptUpload(
                ByteArrayInputStream(transferRequest(73, remote, wav.size.toLong())), untouched
            )
        }
        assertEquals(0, untouched.size())
        // Invalid transition must not implicitly send a download request.
        val next = ready()
        next.queueUpload(ByteArrayOutputStream())
        reject {
            next.acceptUpload(
                ByteArrayInputStream(transferRequest(73, "Unknown\\Track.wav", wav.size.toLong())),
                ByteArrayOutputStream()
            )
        }
        reject { next.queueUpload(ByteArrayOutputStream()) }
        assertTrue(temp.root.listFiles().isNullOrEmpty())
    }

    @Test fun wrongDigestDeletesPartialAndDoesNotAllowRetry() {
        val session = ready()
        session.queueUpload(ByteArrayOutputStream())
        session.acceptUpload(
            ByteArrayInputStream(transferRequest(73, remote, wav.size.toLong())),
            ByteArrayOutputStream()
        )
        val modified = wav.copyOf().apply { this[lastIndex] = 7 }
        reject {
            session.receiveFile(
                ByteArrayInputStream(le32(73) + modified),
                ByteArrayOutputStream(), temp.root
            )
        }
        assertTrue(temp.root.listFiles().isNullOrEmpty())
        reject { session.receiveFile(ByteArrayInputStream(le32(73) + wav), ByteArrayOutputStream(), temp.root) }
    }

    private fun ready(): A0LicensedOneFileSession.Session {
        val result = A0LicensedOneFileSession.plan(reference(true), listing())
        assertTrue(result is A0LicensedOneFileSession.Plan.Ready)
        return (result as A0LicensedOneFileSession.Plan.Ready).session
    }

    private fun reference(reviewed: Boolean) = A0OpenAudioCandidateGate.ReviewedReference(
        sourcePage = "https://example.org/licensed/own-tone",
        licenseIdentifier = "CC0-1.0",
        expectedFilename = "OwnedTone.wav",
        expectedSize = wav.size.toLong(),
        independentlyKnownSha256 = hash,
        rightsReviewedOutsideSoulseek = reviewed
    )

    private fun listing() = A0OpenAudioCandidateGate.PublicPeerListing(
        remoteFilename = remote, reportedSize = wav.size.toLong(), locked = false, freeSlot = true
    )

    private fun transferRequest(token: Int, path: String, bytes: Long): ByteArray =
        frame(40, le32(1) + le32(token) + string(path) + le64(bytes))

    private fun frame(code: Int, payload: ByteArray): ByteArray {
        val body = le32(code) + payload
        return le32(body.size) + body
    }
    private fun le32(v: Int) = ByteArray(4) { (v ushr (it * 8)).toByte() }
    private fun le64(v: Long) = ByteArray(8) { (v ushr (it * 8)).toByte() }
    private fun string(s: String) = s.toByteArray(Charsets.UTF_8).let { le32(it.size) + it }

    private fun wavFixture(): ByteArray {
        val header = ByteArray(44)
        fun mark(index: Int, name: String) { name.toByteArray(Charsets.US_ASCII).copyInto(header, index) }
        fun int(index: Int, n: Int) { repeat(4) { header[index + it] = (n ushr (8 * it)).toByte() } }
        fun short(index: Int, n: Int) { repeat(2) { header[index + it] = (n ushr (8 * it)).toByte() } }
        mark(0, "RIFF"); int(4, 36 + 256); mark(8, "WAVE")
        mark(12, "fmt "); int(16, 16); short(20, 1); short(22, 1)
        int(24, 8000); int(28, 16000); short(32, 2); short(34, 16)
        mark(36, "data"); int(40, 256)
        return header + ByteArray(256) { (it and 0x7f).toByte() }
    }
    private fun reject(op: () -> Unit) {
        try { op(); fail("Must reject without creating verified output") }
        catch (_: IllegalStateException) { } catch (_: IOException) { }
    }
}
