package com.music.bitchord.acquisition.a0

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Only the authenticated server reader uses this wrapper. A timeout with
 * partially consumed framed data is NOT a clean frame boundary: fail closed
 * rather than parsing the remainder as a new Soulseek message.
 *
 * Never persists actual byte values, sizes of peer data, or packet contents.
 */
internal class A0FrameReadProgress(input: InputStream) : FilterInputStream(input) {
    var bytesConsumedInFrame: Int = 0
        private set

    val atFrameBoundary: Boolean
        get() = bytesConsumedInFrame == 0

    /** Invoke only after a complete valid or completely rejected server frame. */
    fun nextFrame() {
        bytesConsumedInFrame = 0
    }

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) bytesConsumedInFrame++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = super.read(buffer, offset, length)
        if (bytes > 0) bytesConsumedInFrame += bytes
        return bytes
    }
}
