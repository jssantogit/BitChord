package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A0 modern Soulseek P-upload request and subsequent F byte-transfer core.
 *
 * Input/output streams are owned by the caller: NO sockets, DNS, credentials,
 * user interfaces, peer discovery or production download entrypoint.
 *
 * IMPORTANT: the caller MUST separately authenticate/associate the incoming P
 * and F sockets with a peer obtained via the real Soulseek server. File tokens
 * only correlate messages and ARE NOT identity or authorisation credentials.
 *
 * Wire reference: Nicotine+ Soulseek Protocol Documentation, Peer codes 40/41
 * (direction 1 upload), FileTransferInit, FileOffset.
 */
internal object A0ModernIncomingTransfer {
    private const val MAX_FRAME_BYTES = 4096
    private const val MAX_FILENAME_BYTES = 1024
    private const val MAX_PROBE_BYTES = 64L * 1024L * 1024L
    private val SHA256 = Regex("[a-fA-F0-9]{64}")

    internal class Ticket internal constructor(
        val token: Int,
        val expectedByteCount: Long,
        val independentlyTrustedSha256: String?
    )

    /**
     * Receives one bounded Peer TransferRequest (40, direction=1, token,
     * filename, size) from an ALREADY VERIFIED peer-message channel.
     *
     * No response is emitted for malformed/unsolicited requests. The accepted
     * UploadResponse (41) is exactly: token + bool=1, NO appended file size.
     * A Ticket contains no public username, remote filename or source address.
     */
    fun acceptUpload(
        incomingP: InputStream,
        outgoingP: OutputStream,
        expectedRemoteFilename: String,
        expectedByteCount: Long,
        independentlyTrustedSha256: String
    ): Ticket = acceptInternal(incomingP,outgoingP,expectedRemoteFilename,
        expectedByteCount,independentlyTrustedSha256)

    fun acceptObservedUpload(
        incomingP:InputStream,
        outgoingP:OutputStream,
        expectedRemoteFilename:String,
        expectedByteCount:Long
    ):Ticket {
        require(expectedByteCount in 44L..64L*1024L*1024L) {
            "A0 observed mode is capped at 64 MiB"
        }
        return acceptInternal(incomingP,outgoingP,expectedRemoteFilename,
            expectedByteCount,null)
    }

    private fun acceptInternal(
        incomingP:InputStream,
        outgoingP:OutputStream,
        expectedRemoteFilename:String,
        expectedByteCount:Long,
        independentlyTrustedSha256:String?
    ):Ticket {
        require(expectedByteCount in 44L..MAX_PROBE_BYTES) {
            "A0 modern probe requires a bounded exact source byte count"
        }
        require(independentlyTrustedSha256 == null ||
            SHA256.matches(independentlyTrustedSha256)) {
            "A0 modern probe requires an independent source SHA-256"
        }
        require(isSafeRemoteName(expectedRemoteFilename)) {
            "A0 modern probe requires one previously reviewed remote filename"
        }
        val frameSize = readLE32(incomingP)
        if (frameSize !in 25..MAX_FRAME_BYTES) malformed()
        val frame = ByteArrayInputStream(readExact(incomingP, frameSize))
        if (readLE32(frame) != 40) malformed() // TransferRequest
        if (readLE32(frame) != 1) malformed()  // peer is UPLOADING to us
        val token = readLE32(frame)
        if (token <= 0) malformed()
        val filenameLength = readLE32(frame)
        if (filenameLength !in 1..MAX_FILENAME_BYTES ||
            filenameLength > frame.available() - 8) malformed()
        val filename = String(readExact(frame, filenameLength), Charsets.UTF_8)
        if (filename != expectedRemoteFilename) malformed()
        val size = readLE64(frame)
        if (size != expectedByteCount || frame.available() != 0) malformed()

        // UploadResponse for direction=1 has NO filesize; that is only part
        // of the legacy DownloadResponse (direction=0) format.
        val payload = le32(41) + le32(token) + byteArrayOf(1)
        outgoingP.write(le32(payload.size))
        outgoingP.write(payload)
        outgoingP.flush()
        return Ticket(token, expectedByteCount, independentlyTrustedSha256?.lowercase())
    }

    /**
     * Invoked ONLY after the caller has verified the peer and completed
     * inbound F socket initialization (or an approved indirect connection).
     *
     * Incoming F then sends a raw LE32 FileTransferInit token; our first
     * outbound bytes MUST be raw LE64 FileOffset=0, followed by file bytes
     * from the remote uploader. No resume or nonzero offset is supported.
     * Caller must configure a socket read timeout and control cancellation.
     */
    fun receiveAfterFConnection(
        incomingF: InputStream,
        outgoingF: OutputStream,
        ticket: Ticket,
        privateDirectory: File,
        isCancelled: () -> Boolean = { false }
    ): A0VerifiedByteStager.Receipt {
        if (readLE32(incomingF) != ticket.token) malformed()
        if (isCancelled()) throw IOException("A0 modern incoming transfer cancelled")
        outgoingF.write(ByteArray(8)) // raw uint64 little-endian offset 0
        outgoingF.flush()
        val trusted=ticket.independentlyTrustedSha256
        return if(trusted==null) A0VerifiedByteStager.stageObserved(
            source=incomingF,
            privateDirectory=privateDirectory,
            expectedByteCount=ticket.expectedByteCount,
            isCancelled=isCancelled
        ) else A0VerifiedByteStager.stage(
            source=incomingF,
            privateDirectory=privateDirectory,
            expectedByteCount=ticket.expectedByteCount,
            expectedSha256Hex=trusted,
            isCancelled=isCancelled
        )
    }

    internal fun isSafeRemoteName(name: String): Boolean {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_FILENAME_BYTES ||
            name.any { it.code < 32 } || name.startsWith("/") ||
            Regex("^[A-Za-z]:").containsMatchIn(name)
        ) return false
        return name.replace('\\', '/').split('/').none {
            it.isEmpty() || it == "." || it == ".."
        }
    }

    private fun readLE32(input: InputStream): Int {
        val bytes = readExact(input, 4)
        return (bytes[0].toInt() and 255) or
            ((bytes[1].toInt() and 255) shl 8) or
            ((bytes[2].toInt() and 255) shl 16) or
            ((bytes[3].toInt() and 255) shl 24)
    }

    private fun readLE64(input: InputStream): Long {
        val bytes = readExact(input, 8)
        var result = 0L
        for (i in bytes.indices) {
            result = result or ((bytes[i].toLong() and 255L) shl (i * 8))
        }
        return result
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(out, offset, count - offset)
            if (n < 0) throw EOFException("A0 modern peer protocol ended prematurely")
            if (n == 0) throw IOException("A0 modern peer protocol stopped progressing")
            offset += n
        }
        return out
    }

    private fun le32(n: Int): ByteArray = ByteArray(4) { i ->
        (n ushr (i * 8)).toByte()
    }

    private fun malformed(): Nothing =
        throw IOException("Malformed or unsolicited A0 modern peer upload request")
}
