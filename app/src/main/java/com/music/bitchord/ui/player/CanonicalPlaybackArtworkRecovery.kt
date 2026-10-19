package com.music.bitchord.ui.player

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoadResult
import kotlinx.coroutines.delay

/**
 * Navidrome can restart without any Android connectivity broadcast (Wi-Fi stays up).
 * Only transient provider transport failure is retried. No other failure may
 * restart a credential read or bypass selected canonical artwork identity.
 */
internal fun canonicalArtworkRecoveryDelayMs(
    reason: String,
    transientFailures: Int,
): Long? {
    if (reason != "NETWORK_UNAVAILABLE") return null
    return when {
        transientFailures <= 1 -> 2_000L
        transientFailures == 2 -> 4_000L
        transientFailures == 3 -> 8_000L
        else -> 15_000L
    }
}

/**
 * Reconnect while this image remains composed. The caller owns the coroutine:
 * selection/account change or screen disposal cancels the delay and all retries.
 * Successful disk-cache reads return immediately; no negative-result cache.
 */
internal suspend fun awaitRecoverableCanonicalArtwork(
    load: suspend () -> OpenSubsonicArtworkLoadResult,
    wait: suspend (Long) -> Unit = { delay(it) },
): OpenSubsonicArtworkLoadResult {
    var transientFailures = 0
    while (true) {
        when (val result = load()) {
            is OpenSubsonicArtworkLoadResult.Ready -> return result
            is OpenSubsonicArtworkLoadResult.Placeholder -> {
                transientFailures++
                val retryAfter = canonicalArtworkRecoveryDelayMs(
                    result.reason, transientFailures,
                ) ?: return result
                wait(retryAfter)
            }
        }
    }
}
