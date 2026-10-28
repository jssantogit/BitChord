package com.music.bitchord.download

import java.util.concurrent.atomic.AtomicLong

/**
 * Activity foreground state can become true again while an old worker is
 * still exiting. Revocation is monotonic: no later onStart can reuse an old
 * manual A3 publication authorization.
 */
internal class SoulseekManualLifecycleGate {
    private val epoch=AtomicLong(0L)

    fun token():Long=epoch.get()

    /** Call from onStop before any UI state is reset on a future onStart. */
    fun revoke():Long=epoch.incrementAndGet()

    fun isCurrent(token:Long):Boolean=epoch.get()==token
}
