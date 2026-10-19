package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoadResult
import com.music.bitchord.data.catalog.provider.opensubsonic.isTransientArtworkHttpStatus
import com.music.bitchord.ui.player.awaitRecoverableCanonicalArtwork
import com.music.bitchord.ui.player.canonicalArtworkRecoveryDelayMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CanonicalPlaybackArtworkRecoveryTest {
    @Test
    fun `Navidrome offline artwork automatically returns after server comes back without reselecting track`() = runTest {
        var online = false
        var requests = 0
        val retryDelays = mutableListOf<Long>()
        val loaded = awaitRecoverableCanonicalArtwork(
            load = {
                requests++
                if (online) OpenSubsonicArtworkLoadResult.Ready(
                    bytes = byteArrayOf(1, 2, 3), fromCache = false,
                ) else OpenSubsonicArtworkLoadResult.Placeholder("NETWORK_UNAVAILABLE")
            },
            wait = { millis ->
                retryDelays += millis
                online = true // Same selected Recording/MediaAsset, remote host restarted.
            },
        )
        val ready = loaded as OpenSubsonicArtworkLoadResult.Ready
        assertArrayEquals(byteArrayOf(1, 2, 3), ready.bytes)
        assertEquals(listOf(2_000L), retryDelays)
        assertEquals(2, requests)
    }

    @Test
    fun `offline retry spacing is capped while a visible row remains composed`() = runTest {
        var requests = 0
        val retryDelays = mutableListOf<Long>()
        val outcome = awaitRecoverableCanonicalArtwork(
            load = {
                requests++
                if (requests == 7) {
                    OpenSubsonicArtworkLoadResult.Ready(byteArrayOf(9), fromCache = false)
                } else {
                    OpenSubsonicArtworkLoadResult.Placeholder("NETWORK_UNAVAILABLE")
                }
            },
            wait = { retryDelays += it },
        )
        assertTrue(outcome is OpenSubsonicArtworkLoadResult.Ready)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L), retryDelays)
        assertEquals(7, requests)
    }

    @Test
    fun `account auth identity and malformed artwork failures are never retried`() = runTest {
        val permanent = listOf(
            "ACCOUNT_MISMATCH", "CREDENTIAL_UNAVAILABLE", "AUTHENTICATION_REJECTED",
            "REQUEST_REJECTED", "ARTWORK_NOT_AVAILABLE", "MEDIA_ASSET_NOT_FOUND",
            "SELECTION_MISMATCH", "EMPTY_ARTWORK", "INVALID_ARTWORK_REFERENCE",
            "ACCOUNT_NOT_CONFIGURED",
        )
        for (reason in permanent) {
            var attempts = 0
            val outcome = awaitRecoverableCanonicalArtwork(
                load = {
                    attempts++
                    OpenSubsonicArtworkLoadResult.Placeholder(reason)
                },
                wait = { fail("Must not retry $reason") },
            )
            assertEquals(reason, (outcome as OpenSubsonicArtworkLoadResult.Placeholder).reason)
            assertEquals(1, attempts)
            assertEquals(null, canonicalArtworkRecoveryDelayMs(reason, 1))
        }
    }

    @Test
    fun `successful cached image does not trigger any retry or credential request`() = runTest {
        var calls = 0
        val result = awaitRecoverableCanonicalArtwork(
            load = {
                calls++
                OpenSubsonicArtworkLoadResult.Ready(byteArrayOf(6), fromCache = true)
            },
            wait = { fail("Cache hit must never schedule retry") },
        )
        assertTrue((result as OpenSubsonicArtworkLoadResult.Ready).fromCache)
        assertEquals(1, calls)
    }

    @Test
    fun `screen removal cancels pending retries immediately`() = runTest {
        var attempts = 0
        try {
            awaitRecoverableCanonicalArtwork(
                load = {
                    attempts++
                    OpenSubsonicArtworkLoadResult.Placeholder("NETWORK_UNAVAILABLE")
                },
                wait = { throw CancellationException("composition disposed") },
            )
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `only transient server http errors retry not auth or missing artwork`() {
        assertTrue(isTransientArtworkHttpStatus(429))
        assertTrue(isTransientArtworkHttpStatus(500))
        assertTrue(isTransientArtworkHttpStatus(502))
        assertTrue(isTransientArtworkHttpStatus(503))
        assertFalse(isTransientArtworkHttpStatus(401))
        assertFalse(isTransientArtworkHttpStatus(403))
        assertFalse(isTransientArtworkHttpStatus(404))
        assertFalse(isTransientArtworkHttpStatus(400))
    }
}
