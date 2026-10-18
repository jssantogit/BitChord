package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MetadataLookupResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzMetadataEnrichmentProvider
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzRateLimiterTest {

    @Test
    fun artistAndRecordingLookupsShareOneGlobalOneSecondWindow() = runBlocking {
        val clock = MutableClock()
        val limiter = MusicBrainzRateLimiter(
            nowMillis = clock::nowMillis,
            delayMillis = clock::delay,
        )
        val transport = TimedTransport(clock)
        val provider = MusicBrainzMetadataEnrichmentProvider(
            MusicBrainzClient(
                transport = transport,
                rateLimiter = limiter,
                userAgent = TEST_USER_AGENT,
            ),
        )

        listOf(
            async { provider.lookupArtist(ARTIST_MBID) },
            async { provider.lookupRecording(RECORDING_MBID) },
            async { provider.lookupRelease(RELEASE_MBID) },
        ).awaitAll().forEach { result -> assertTrue(result is MetadataLookupResult.Success<*>) }

        assertEquals(3, transport.startedAt.size)
        transport.startedAt.zipWithNext().forEach { (first, second) ->
            assertTrue("MusicBrainz requests must start >= 1000 ms apart", second - first >= 1_000L)
        }
    }

    @Test
    fun cancellationWhileWaitingDoesNotPoisonLimiter() = runBlocking {
        val clock = MutableClock()
        var cancelNextDelay = false
        val limiter = MusicBrainzRateLimiter(
            nowMillis = clock::nowMillis,
            delayMillis = { millis ->
                if (cancelNextDelay) {
                    cancelNextDelay = false
                    throw CancellationException("cancelled fixture")
                }
                clock.delay(millis)
            },
        )
        val starts = mutableListOf<Long>()

        limiter.runLimited { starts += clock.nowMillis() }
        cancelNextDelay = true
        try {
            limiter.runLimited { starts += clock.nowMillis() }
            error("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation must escape rather than being sanitized.
        }
        limiter.runLimited { starts += clock.nowMillis() }

        assertEquals(listOf(0L, 1_000L), starts)
    }

    private class MutableClock {
        private var value = 0L
        fun nowMillis(): Long = value
        suspend fun delay(millis: Long) {
            value += millis
        }
    }

    private class TimedTransport(
        private val clock: MutableClock,
    ) : MusicBrainzTransport {
        val startedAt = mutableListOf<Long>()

        override suspend fun get(
            path: String,
            parameters: Map<String, String>,
            userAgent: String,
        ): String {
            startedAt += clock.nowMillis()
            return when {
                path.startsWith("artist/") -> """{"id":"$ARTIST_MBID","name":"Artist"}"""
                path.startsWith("recording/") -> """{"id":"$RECORDING_MBID","title":"Track"}"""
                path.startsWith("release/") -> """{"id":"$RELEASE_MBID","title":"Album"}"""
                else -> error("Unexpected path $path")
            }
        }
    }

    companion object {
        private const val TEST_USER_AGENT = "Bitchord/test (https://github.com/jssantogit/BitChord)"
        private const val ARTIST_MBID = "11111111-1111-1111-1111-111111111111"
        private const val RECORDING_MBID = "22222222-2222-2222-2222-222222222222"
        private const val RELEASE_MBID = "44444444-4444-4444-4444-444444444444"
    }
}
