package com.music.bitchord.playback

/**
 * Accumulates only wall-clock audio deltas produced by the playback sampler.
 * Player position is intentionally absent from this API, so seeking can never
 * be mistaken for listened time. A pause contributes nothing; resumed audio is
 * simply added by subsequent positive deltas.
 */
internal class PlaybackListeningProgress {
    var listenedDurationMs: Long = 0L
        private set

    var recordingDurationMs: Long? = null
        private set

    fun observeAudioDelta(deltaMs: Long, recordingDurationMs: Long?) {
        require(deltaMs >= 0L)
        listenedDurationMs += deltaMs
        if (recordingDurationMs != null && recordingDurationMs > 0L) {
            this.recordingDurationMs = recordingDurationMs
        }
    }
}
