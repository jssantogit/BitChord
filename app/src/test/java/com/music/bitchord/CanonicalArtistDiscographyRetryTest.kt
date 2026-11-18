package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchFailure
import com.music.bitchord.data.catalog.metadata.retryTransientArtistDiscography
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NewJeans and other artists must recover from a transient MusicBrainz
 * availability hiccup without inventing a release or retrying forever. */
class CanonicalArtistDiscographyRetryTest {
    @Test fun oneTransientFailureGetsOneBoundedRetry() = runBlocking {
        var attempts = 0
        var delays = 0
        val out = retryTransientArtistDiscography(
            fetch = {
                attempts++
                if (attempts == 1)
                    MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.UNAVAILABLE)
                else MusicBrainzCatalogGraphResult.Success("verified")
            },
            delayBeforeRetry = { delays++ },
        )
        assertEquals(2, attempts)
        assertEquals(1, delays)
        assertEquals("verified", (out as MusicBrainzCatalogGraphResult.Success).value)
    }

    @Test fun malformedAndInvalidResponsesNeverSpamMusicBrainz() = runBlocking {
        var attempts = 0
        val out = retryTransientArtistDiscography(
            fetch = {
                attempts++
                MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE)
            },
            delayBeforeRetry = { error("No retry for malformed data") },
        )
        assertEquals(1, attempts)
        assertTrue(out is MusicBrainzCatalogGraphResult.Failure)
    }

    @Test fun repeatOutageStopsAfterSecondAttempt() = runBlocking {
        var attempts = 0
        val out = retryTransientArtistDiscography(
            fetch = {
                attempts++
                MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.UNAVAILABLE)
            },
            delayBeforeRetry = {},
        )
        assertEquals(2, attempts)
        assertTrue(out is MusicBrainzCatalogGraphResult.Failure)
    }
}
