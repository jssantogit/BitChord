package com.music.bitchord.data.listening

/**
 * Read-only view of the analytical ListeningSession that is current at a supplied instant.
 * Recommendation can inspect session context without mutating session lifecycle.
 */
internal interface ListeningSessionContextReader {
    suspend fun currentSession(atEpochMs: Long): ListeningSession?

    suspend fun eventsForSession(sessionId: String): List<ListeningEvent>
}

/** Shared analytical liveness policy used by both ingestion rollover and read-only context. */
internal object ListeningSessionPolicy {
    const val INACTIVITY_TIMEOUT_MS: Long = 30L * 60L * 1000L

    fun isCurrent(session: ListeningSession, atEpochMs: Long): Boolean {
        if (session.endedAtEpochMs != null) return false
        if (atEpochMs < session.lastActivityAtEpochMs) return false

        val inactivityMs = try {
            Math.subtractExact(atEpochMs, session.lastActivityAtEpochMs)
        } catch (_: ArithmeticException) {
            return false
        }
        return inactivityMs < INACTIVITY_TIMEOUT_MS
    }
}
