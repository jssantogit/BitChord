package com.music.bitchord.acquisition.a0

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Read-only progress wrapper for a single server login reply.
 *
 * This observes only how much of the 4-byte frame header has arrived,
 * and whether payload bytes have begun. No payload, response length,
 * user, address, token, or error text is retained or exported.
 */
internal class A0LoginReadProgress(
    source: InputStream,
    private val onFirstByte: () -> Unit = {},
) : FilterInputStream(source) {

    enum class Phase { NO_BYTES, PARTIAL_HEADER, HEADER_ONLY, PAYLOAD_SEEN }

    @Volatile
    private var observedBytes: Int = 0

    val phase: Phase
        get() = when (observedBytes) {
            0 -> Phase.NO_BYTES
            in 1..3 -> Phase.PARTIAL_HEADER
            4 -> Phase.HEADER_ONLY
            else -> Phase.PAYLOAD_SEEN
        }

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) observe(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val readCount = super.read(b, off, len)
        if (readCount > 0) observe(readCount)
        return readCount
    }

    private fun observe(n: Int) {
        if (observedBytes == 0) {
            // Observability must not break legitimate protocol I/O.
            runCatching { onFirstByte() }
        }
        // A bounded counter, enough to categorize the frame receive phase.
        observedBytes = (observedBytes + n).coerceAtMost(5)
    }

    override fun toString(): String = "A0LoginReadProgress(phase=${phase.name})"
}
