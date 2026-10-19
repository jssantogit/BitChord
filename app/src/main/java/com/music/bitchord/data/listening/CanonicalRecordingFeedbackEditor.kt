package com.music.bitchord.data.listening

/**
 * Explicit, user-invoked mutations only. The availability check must be
 * performed again immediately before a write; display snapshots are not
 * permission to mutate a now-unavailable Recording.
 *
 * The cross-database Catalog/Listening check is best-effort fail-closed at
 * invocation time, not a claim of atomicity between separate Room databases.
 */
internal class CanonicalRecordingFeedbackEditor(
    private val store: RecordingRatingStore,
    private val isAvailable: suspend (String) -> Boolean,
) {
    suspend fun set(recordingId: String, value: Double): Boolean {
        require(recordingId.isNotBlank()) { "Canonical RecordingId required" }
        require(value.isFinite() && value in 1.0..5.0) { "Explicit rating must be finite in 1..5" }
        if (!isAvailable(recordingId)) return false
        store.setRecordingRating(recordingId, value)
        return true
    }

    suspend fun clear(recordingId: String): Boolean {
        require(recordingId.isNotBlank()) { "Canonical RecordingId required" }
        if (!isAvailable(recordingId)) return false
        store.clearRecordingRating(recordingId)
        return true
    }
}
