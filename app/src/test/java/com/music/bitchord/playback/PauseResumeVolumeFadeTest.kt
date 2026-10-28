package com.music.bitchord.playback

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PauseResumeVolumeFadeTest {
    @Test fun `pause waits for fade out before pausing the playhead`() = runTest {
        val deck = FakeDeck(playingInitially = true)
        val fade = PauseResumeVolumeFade(this, { 200 }, { false }, { testScheduler.currentTime })
        fade.pause(deck.player)
        runCurrent()
        assertEquals(0, deck.pauseCalls)
        advanceTimeBy(112)
        runCurrent()
        assertTrue(deck.volume in 0.3f..0.6f)
        assertTrue(deck.playWhenReady)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(1, deck.pauseCalls)
        assertFalse(deck.playWhenReady)
        assertEquals(1f, deck.volume, 0.00001f)
    }

    @Test fun `resume starts silent and fades after audible rather than during buffering`() = runTest {
        val deck = FakeDeck(playingInitially = false)
        deck.audibleOnPlay = false
        val fade = PauseResumeVolumeFade(this, { 200 }, { false }, { testScheduler.currentTime })
        fade.play(deck.player)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0f, deck.volume, 0f)
        assertTrue(deck.playWhenReady)
        deck.audible = true
        advanceTimeBy(250)
        runCurrent()
        assertEquals(1f, deck.volume, 0.00001f)
    }

    @Test fun `play during pending pause reverses gain and must never perform stale pause`() = runTest {
        val deck = FakeDeck(playingInitially = true)
        val fade = PauseResumeVolumeFade(this, { 200 }, { false }, { testScheduler.currentTime })
        fade.pause(deck.player)
        runCurrent()
        advanceTimeBy(80)
        runCurrent()
        val current = deck.volume
        fade.play(deck.player)
        assertEquals(current, deck.volume, 0.00001f)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(0, deck.pauseCalls)
        assertTrue(deck.playWhenReady)
        assertEquals(1f, deck.volume, 0.00001f)
    }

    @Test fun `pause while fade in has not reached audio stops immediately without residual mute`() = runTest {
        val deck = FakeDeck(playingInitially = false)
        deck.audibleOnPlay = false
        val fade = PauseResumeVolumeFade(this, { 200 }, { false }, { testScheduler.currentTime })
        fade.play(deck.player)
        runCurrent()
        fade.pause(deck.player)
        runCurrent()
        assertEquals(1, deck.pauseCalls)
        assertEquals(1f, deck.volume, 0.00001f)
        assertFalse(deck.playWhenReady)
    }

    @Test fun `overlap takes priority and reset clears abandoned envelope`() = runTest {
        val deck = FakeDeck(playingInitially = true)
        var overlap = false
        val fade = PauseResumeVolumeFade(this, { 200 }, { overlap }, { testScheduler.currentTime })
        fade.pause(deck.player)
        runCurrent()
        advanceTimeBy(64)
        runCurrent()
        overlap = true
        advanceTimeBy(16)
        runCurrent()
        assertEquals(1, deck.pauseCalls)
        overlap = false
        fade.reset()
        assertEquals(1f, deck.volume, 0.00001f)
    }

    @Test fun `handoff during pause fade pauses newly adopted session deck too`() = runTest {
        val outgoing = FakeDeck(playingInitially = true)
        val incoming = FakeDeck(playingInitially = true)
        var overlap = false
        var currentOwner: Player = outgoing.player
        val fade = PauseResumeVolumeFade(
            scope = this,
            durationMs = { 200 },
            overlapActive = { overlap },
            clockMs = { testScheduler.currentTime },
            sessionOwner = { currentOwner },
        )
        fade.pause(outgoing.player)
        runCurrent()
        advanceTimeBy(64)
        runCurrent()
        currentOwner = incoming.player
        overlap = true
        advanceTimeBy(16)
        runCurrent()
        assertEquals(1, outgoing.pauseCalls)
        assertEquals(1, incoming.pauseCalls)
        assertFalse(incoming.playWhenReady)
    }

    @Test fun `repeated pause keeps original deadline instead of prolonging fade`() = runTest {
        val deck = FakeDeck(playingInitially = true)
        val fade = PauseResumeVolumeFade(this, { 200 }, { false }, { testScheduler.currentTime })
        fade.pause(deck.player)
        runCurrent()
        advanceTimeBy(80)
        runCurrent()
        fade.pause(deck.player)
        advanceTimeBy(160)
        runCurrent()
        assertEquals(1, deck.pauseCalls)
        assertFalse(deck.playWhenReady)
        assertEquals(1f, deck.volume, 0f)
    }

    @Test fun `ended stream cancels pending fade in without leaving player muted`() = runTest {
        val deck = FakeDeck(playingInitially = false)
        deck.audibleOnPlay = false
        val fade = PauseResumeVolumeFade(this, { 200 }, { false }, { testScheduler.currentTime })
        fade.play(deck.player)
        runCurrent()
        deck.playbackState = Player.STATE_ENDED
        advanceTimeBy(32)
        runCurrent()
        assertEquals(1f, deck.volume, 0f)
    }

    @Test fun `decoder error during startup cannot leave session volume muted`() = runTest {
        val deck = FakeDeck(playingInitially = false)
        deck.audibleOnPlay = false
        val fade = PauseResumeVolumeFade(this, { 200 }, { false }, { testScheduler.currentTime })
        fade.play(deck.player)
        runCurrent()
        deck.playerError = PlaybackException(
            "simulated decoder error",
            null,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
        advanceTimeBy(32)
        runCurrent()
        assertEquals(1f, deck.volume, 0f)
    }

    @Test fun `a second play command recovers after an external pause during startup`() = runTest {
        val deck = FakeDeck(playingInitially = false)
        deck.audibleOnPlay = false
        val fade = PauseResumeVolumeFade(this, { 200 }, { false }, { testScheduler.currentTime })
        fade.play(deck.player)
        runCurrent()
        deck.playWhenReady = false // interrupted outside MediaSession (e.g. audio focus)
        fade.play(deck.player)
        runCurrent()
        assertEquals(2, deck.playCalls)
        assertTrue(deck.playWhenReady)
    }

    @Test fun `failed play command restores volume instead of stranding a muted deck`() = runTest {
        val deck = FakeDeck(playingInitially = false)
        deck.throwOnPlay = true
        val fade = PauseResumeVolumeFade(this, { 200 }, { false }, { testScheduler.currentTime })
        try {
            fade.play(deck.player)
            throw AssertionError("Expected thrown play failure")
        } catch (_: IllegalStateException) {
            assertEquals(1f, deck.volume, 0f)
        }
    }

    @Test fun `diagnostics report one start and one completion without per frame noise`() = runTest {
        val deck = FakeDeck(playingInitially = true)
        val events = mutableListOf<PauseResumeFadeEvent>()
        val fade = PauseResumeVolumeFade(
            scope = this,
            durationMs = { 200 },
            overlapActive = { false },
            clockMs = { testScheduler.currentTime },
            onEvent = { event, _ -> events += event },
        )
        fade.pause(deck.player)
        advanceTimeBy(240)
        runCurrent()
        assertEquals(
            listOf(PauseResumeFadeEvent.FADE_OUT_STARTED, PauseResumeFadeEvent.FADE_OUT_COMPLETED),
            events,
        )
    }

    @Test fun `aborted fade emits a cancellation reason, not a completion`() = runTest {
        val deck = FakeDeck(playingInitially = true)
        val events = mutableListOf<PauseResumeFadeEvent>()
        val fade = PauseResumeVolumeFade(
            scope = this,
            durationMs = { 200 },
            overlapActive = { false },
            clockMs = { testScheduler.currentTime },
            onEvent = { event, _ -> events += event },
        )
        fade.pause(deck.player)
        runCurrent()
        advanceTimeBy(32)
        runCurrent()
        fade.reset()
        assertEquals(
            listOf(PauseResumeFadeEvent.FADE_OUT_STARTED, PauseResumeFadeEvent.CANCELED),
            events,
        )
    }

    @Test fun `disabled preference bypasses the entire envelope`() = runTest {
        val deck = FakeDeck(playingInitially = true)
        val fade = PauseResumeVolumeFade(this, { 0 }, { false }, { testScheduler.currentTime })
        fade.pause(deck.player)
        assertEquals(1, deck.pauseCalls)
        assertEquals(1f, deck.volume, 0f)
    }

    private class FakeDeck(playingInitially: Boolean) {
        var volume = 1f
        var playWhenReady = playingInitially
        var audible = playingInitially
        var audibleOnPlay = true
        var pauseCalls = 0
        var playCalls = 0
        var playbackState = Player.STATE_READY
        var playerError: PlaybackException? = null
        var throwOnPlay = false

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "play" -> {
                    playCalls++
                    if (throwOnPlay) throw IllegalStateException("player released")
                    playWhenReady = true
                    audible = audibleOnPlay
                    null
                }
                "pause" -> { playWhenReady = false; audible = false; pauseCalls++; null }
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; null }
                "getPlayWhenReady" -> playWhenReady
                "getPlaybackState" -> playbackState
                "getPlayerError" -> playerError
                "isPlaying" -> audible
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeDeck"
                else -> throw UnsupportedOperationException("Unexpected Player call: ${method.name}")
            }
        } as Player
    }
}
