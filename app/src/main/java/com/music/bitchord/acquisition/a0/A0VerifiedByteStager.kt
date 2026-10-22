package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A0 local byte-staging feasibility experiment, not a Soulseek download manager.
 * Caller must explicitly supply an app-private directory; this method cannot infer
 * Android storage ownership. No peer, credentials, RecordingId or source filenames are used.
 *
 * Expected SHA-256 must originate from an independently trusted fixture/source. Soulseek
 * search results DO NOT advertise verified hashes; do not treat metadata as trust.
 */
internal object A0VerifiedByteStager {
    private const val MAX_TEST_TRANSFER_BYTES = 512L * 1024L * 1024L
    private const val READ_BUFFER_BYTES = 8192

    internal data class Receipt(
        val file: File,
        val byteCount: Long,
        val sha256Hex: String,
        val containerHint: String,
    )

    fun stage(
        source: InputStream,
        privateDirectory: File,
        expectedByteCount: Long,
        expectedSha256Hex: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): Receipt = stageInternal(source,privateDirectory,expectedByteCount,
        expectedSha256Hex,isCancelled,onProgress)

    /**
     * User-selected, bounded TRANSPORT probe only. No independent hash is
     * claimed; calculate a SHA from received bytes, return an .observed scratch
     * artifact (never .verified), and require caller to delete it.
     */
    fun stageObserved(
        source: InputStream,
        privateDirectory: File,
        expectedByteCount: Long,
        isCancelled: () -> Boolean = { false },
    ): Receipt {
        require(expectedByteCount in 44..64L*1024L*1024L) {
            "A0 observed transport must stay within 64 MiB"
        }
        return stageInternal(source,privateDirectory,expectedByteCount,
            null,isCancelled,{})
    }

    private fun stageInternal(
        source:InputStream,
        privateDirectory:File,
        expectedByteCount:Long,
        expectedSha256Hex:String?,
        isCancelled:()->Boolean,
        onProgress:(Long)->Unit
    ):Receipt {
        require(expectedByteCount in 44..MAX_TEST_TRANSFER_BYTES) {
            "A0 test transfer requires a bounded positive byte count"
        }
        require(expectedSha256Hex == null ||
            (expectedSha256Hex.length == 64 && expectedSha256Hex.all {
                it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
            })
        ) { "A0 test requires a trusted hex SHA-256" }
        require(privateDirectory.isDirectory) { "A0 staging directory must exist" }

        val expectedDigest = expectedSha256Hex?.let { trusted ->
            ByteArray(32) { index ->
                trusted.substring(index * 2,index * 2 + 2).toInt(16).toByte()
            }
        }
        val part = File.createTempFile("a0-", ".partial", privateDirectory)
        var committed = false
        try {
            val sha256 = MessageDigest.getInstance("SHA-256")
            val start = ByteArray(12)
            var headerCount = 0
            var count = 0L
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (count < expectedByteCount) {
                    if (isCancelled() || Thread.currentThread().isInterrupted) {
                        throw IOException("A0 local transfer cancelled")
                    }
                    val request = minOf(buffer.size.toLong(), expectedByteCount - count).toInt()
                    val read = source.read(buffer, 0, request)
                    if (read <= 0) throw IOException("Incomplete A0 local transfer")
                    val copy = minOf(start.size - headerCount, read)
                    if (copy > 0) {
                        System.arraycopy(buffer, 0, start, headerCount, copy)
                        headerCount += copy
                    }
                    output.write(buffer, 0, read)
                    sha256.update(buffer, 0, read)
                    count += read
                    onProgress(count)
                }
                output.fd.sync()
            }
            if (isCancelled() || Thread.currentThread().isInterrupted) {
                throw IOException("A0 local transfer cancelled")
            }
            val digest = sha256.digest()
            if (expectedDigest != null &&
                !MessageDigest.isEqual(digest, expectedDigest)) {
                throw IOException("A0 local transfer digest mismatch")
            }
            val containerHint = when {
                start.sliceArray(0..3).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
                    start.sliceArray(8..11).contentEquals("WAVE".toByteArray(Charsets.US_ASCII)) ->
                    "WAV_HEADER_ONLY"
                start.sliceArray(0..3).contentEquals("fLaC".toByteArray(Charsets.US_ASCII)) ->
                    "FLAC_HEADER_ONLY"
                start.sliceArray(0..2).contentEquals("ID3".toByteArray(Charsets.US_ASCII)) ->
                    "ID3_HEADER_ONLY"
                else -> if(expectedDigest != null)
                    throw IOException("A0 fixture did not match supported audio signatures")
                else "UNCLASSIFIED_TRANSPORT_BYTES"
            }
            // File.createTempFile generates a per-call random name; never use a peer filename.
            val suffix=if(expectedDigest==null)".observed" else ".verified"
            val complete = File(privateDirectory, part.name.removeSuffix(".partial") + suffix)
            if (complete.exists()) throw IOException("A0 verified file already exists")
            // Atomic same-directory promotion: partial data never becomes a verified file.
            Files.move(part.toPath(), complete.toPath(), StandardCopyOption.ATOMIC_MOVE)
            committed = true
            return Receipt(complete,count,
                digest.joinToString("") { "%02x".format(it.toInt() and 255) },containerHint)
        } finally {
            if (!committed) part.delete()
        }
    }
}
