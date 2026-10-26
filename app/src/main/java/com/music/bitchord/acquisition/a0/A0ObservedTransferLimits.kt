package com.music.bitchord.acquisition.a0

/**
 * Upper bound for a user-confirmed, observed Soulseek audio file.
 * The transport stages bytes to private disk and then streams them into a
 * pending MediaStore/SAF destination; this is not a heap allocation budget.
 * Independent trusted-fixture probes can use separate stricter contracts.
 */
internal object A0ObservedTransferLimits {
    const val MIN_AUDIO_BYTES = 44L
    const val MAX_AUDIO_BYTES = 256L * 1024L * 1024L
}
