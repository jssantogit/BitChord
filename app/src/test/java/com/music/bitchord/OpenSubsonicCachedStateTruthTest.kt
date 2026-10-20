package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCachedStateProjector
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicConnectionState
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicPublicInternetState
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicSyncFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicCachedStateTruthTest {
    private val account = OpenSubsonicAccountRecord(
        accountId = "home",
        serverUrl = "http://192.168.1.12:4533",
        authMode = OpenSubsonicAuthMode.API_KEY,
        lastHealthReachable = false,
        lastHealthAtEpochSeconds = 900,
        lastSyncAtEpochSeconds = 850,
        lastSyncSucceeded = true,
    )

    @Test fun offlineLanDoesNotEraseCachedLibraryOrImplyPublicInternetOutage() {
        val state = OpenSubsonicCachedStateProjector.project(
            account = account,
            credentialPresent = true,
            cachedRecordingCount = 12,
            syncInProgress = false,
            nowEpochSeconds = 1000,
            maxFreshAgeSeconds = 300,
        )
        assertEquals(OpenSubsonicConnectionState.SERVER_UNREACHABLE, state.server)
        assertEquals(OpenSubsonicPublicInternetState.UNKNOWN, state.publicInternet)
        assertEquals(OpenSubsonicSyncFreshness.FRESH, state.freshness)
        assertTrue(state.cachedLibraryPresent)
        assertTrue(state.libraryEligible)
        assertEquals(12, state.cachedRecordingCount)
        assertFalse(state.playableNowVerified)
    }

    @Test fun explicitAuthFailureAndInvalidReplyRemainDifferentFromLanOutage() {
        for (status in listOf(
            OpenSubsonicConnectionState.AUTHENTICATION_FAILED,
            OpenSubsonicConnectionState.INVALID_RESPONSE,
        )) {
            val state = OpenSubsonicCachedStateProjector.project(
                account, true, 2, false, 1000, 300,
                latestConnection = status,
            )
            assertEquals(status, state.server)
            assertTrue(state.libraryEligible)
            assertFalse(state.playableNowVerified)
        }
    }

    @Test fun syncProgressDoesNotClaimCompletionOrHideCachedRecordings() {
        val state = OpenSubsonicCachedStateProjector.project(
            account, true, 5, true, 1000, 300,
            latestConnection = OpenSubsonicConnectionState.SERVER_REACHABLE,
        )
        assertTrue(state.syncInProgress)
        assertTrue(state.libraryEligible)
        assertTrue(state.cachedLibraryPresent)
        assertFalse(state.playableNowVerified)
    }

    @Test fun freshStaleAndUnknownAreNotGuessedFromLastFailedAttempt() {
        assertEquals(
            OpenSubsonicSyncFreshness.STALE,
            OpenSubsonicCachedStateProjector.project(account, true, 3, false, 1500, 300).freshness,
        )
        assertEquals(
            OpenSubsonicSyncFreshness.UNKNOWN,
            OpenSubsonicCachedStateProjector.project(
                account.copy(lastSyncAtEpochSeconds = 1490, lastSyncSucceeded = false),
                true, 3, false, 1500, 300,
            ).freshness,
        )
        assertEquals(
            OpenSubsonicSyncFreshness.UNKNOWN,
            OpenSubsonicCachedStateProjector.project(
                account.copy(lastSyncAtEpochSeconds = null),
                true, 3, false, 1500, 300,
            ).freshness,
        )
    }

    @Test fun missingCredentialsOrForgottenAccountDoesNotClaimLibraryEligibility() {
        val credentialLost = OpenSubsonicCachedStateProjector.project(
            account, false, 4, false, 1000, 300,
        )
        assertTrue(credentialLost.cachedLibraryPresent)
        assertFalse(credentialLost.libraryEligible)
        val forgotten = OpenSubsonicCachedStateProjector.project(
            null, false, 4, false, 1000, 300,
        )
        assertTrue(forgotten.cachedLibraryPresent)
        assertFalse(forgotten.libraryEligible)
        assertEquals(OpenSubsonicConnectionState.UNKNOWN, forgotten.server)
    }

    @Test fun rejectsUntrustedCountsAndNegativeStaleWindows() {
        for ((count, window) in listOf(-1 to 10L, 0 to -1L)) {
            try {
                OpenSubsonicCachedStateProjector.project(account, true, count, false, 1000, window)
                throw AssertionError("Unsafe arguments accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
