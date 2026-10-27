package com.music.bitchord.diagnostics

/**
 * Small, process-local ownership fence for bounded feature-driven captures.
 * Explicit user Start/Stop always revokes the old feature lease. A stale
 * feature can never stop a later manual or feature capture.
 *
 * Persistence remains in Diagnostics; this class intentionally knows
 * nothing about filenames, credentials, Android, or the user's actions.
 */
internal class ScopedCaptureOwnership {
    data class Lease internal constructor(val generation:Long)

    private var generation=0L
    private var current:Lease?=null

    fun claim():Lease {
        val lease=Lease(++generation)
        current=lease
        return lease
    }

    fun currentLease():Lease?=current

    fun manualTransition() {
        generation++
        current=null
    }

    /** One-shot fence. Caller stops capture only when this returns true. */
    fun release(lease:Lease?):Boolean {
        if(lease==null || current!=lease) return false
        current=null
        generation++
        return true
    }
}
