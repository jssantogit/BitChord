package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #174: session acceptance is NOT evidence that audio started. */
class SmartCollectionAudibleCorrelatorTest {
    @Test fun observedAudioSamplesAreRequiredForAudibleSuccess() {
        var now = 1_000L
        val events = mutableListOf<String>()
        val observer = SmartCollectionAudibleCorrelator(
            nowMillis = { now },
            report = { attempt, stage -> events += "$"+"{attempt}:$"+"{stage}" },
        )
        observer.begin("attempt1", "media-one")
        assertEquals(emptyList<String>(), events)
        observer.onAudioPositionAdvancing("wrong-media")
        assertEquals(emptyList<String>(), events)
        now = 1_500L
        observer.onAudioPositionAdvancing("media-one")
        observer.onAudioPositionAdvancing("media-one")
        assertEquals(listOf("attempt1:audible"), events)
    }

    @Test fun errorAndTimeoutCannotBeMisreportedAsAudible() {
        var now = 20L
        val events = mutableListOf<String>()
        val observer = SmartCollectionAudibleCorrelator(
            nowMillis = { now },
            report = { attempt, stage -> events += "$"+"{attempt}:$"+"{stage}" },
        )
        observer.begin("play1", "track1")
        observer.onPlaybackError()
        observer.onAudioPositionAdvancing("track1")
        assertEquals(listOf("play1:player_error"), events)
        observer.begin("play2", "track2")
        now = 31_000L
        observer.onAudioPositionAdvancing("track2")
        assertEquals(listOf("play1:player_error", "play2:timed_out"), events)
    }

    @Test fun newerExplicitPlaySupersedesPendingOlderAttempt() {
        val events = mutableListOf<String>()
        val observer = SmartCollectionAudibleCorrelator(
            nowMillis = { 100L },
            report = { attempt, stage -> events += "$"+"{attempt}:$"+"{stage}" },
        )
        observer.begin("older", "old-media")
        observer.begin("newer", "new-media")
        observer.onAudioPositionAdvancing("old-media")
        observer.onAudioPositionAdvancing("new-media")
        assertEquals(listOf("older:superseded", "newer:audible"), events)
    }
}
