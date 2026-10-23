package com.music.bitchord.diagnostics

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

internal enum class MainThreadStallSeverity(val wireName: String) {
    MODERATE("moderate"),
    SEVERE("severe"),
    CRITICAL("critical"),
}

internal data class MainThreadStallObservation(
    val durationNanos: Long,
    val severity: MainThreadStallSeverity,
)

/**
 * Pure bounded state machine: one continuous above-threshold episode produces one
 * observation, emitted only after the main thread recovers. Sampling a long stall
 * therefore never creates an event storm.
 */
internal class MainThreadStallState(
    private val thresholdNanos: Long,
) {
    init {
        require(thresholdNanos > 0L) { "thresholdNanos must be greater than zero" }
    }

    private var worstDelayNanos: Long? = null

    fun sample(delayNanos: Long): MainThreadStallObservation? {
        require(delayNanos >= 0L) { "delayNanos must not be negative" }
        if (delayNanos >= thresholdNanos) {
            worstDelayNanos = maxOf(worstDelayNanos ?: 0L, delayNanos)
            return null
        }

        val recoveredDelay = worstDelayNanos ?: return null
        worstDelayNanos = null
        return MainThreadStallObservation(
            durationNanos = recoveredDelay,
            severity = severityFor(recoveredDelay),
        )
    }

    companion object {
        fun severityFor(durationNanos: Long): MainThreadStallSeverity = when {
            durationNanos >= CRITICAL_NANOS -> MainThreadStallSeverity.CRITICAL
            durationNanos >= SEVERE_NANOS -> MainThreadStallSeverity.SEVERE
            else -> MainThreadStallSeverity.MODERATE
        }

        private const val SEVERE_NANOS = 500_000_000L
        private const val CRITICAL_NANOS = 1_000_000_000L
    }
}

/**
 * Debug-only process watchdog. A main-thread heartbeat is observed from a dedicated
 * lightweight worker. Diagnostics are emitted only when a stalled episode recovers,
 * never from the main thread and never once per heartbeat while it remains blocked.
 */
internal object MainThreadStallDetector {
    private const val HEARTBEAT_INTERVAL_MILLIS = 100L
    private const val STALL_THRESHOLD_NANOS = 250_000_000L

    private val installLock = Any()

    @Volatile
    private var installed = false

    private val lastMainHeartbeatNanos = AtomicLong(0L)

    fun install() {
        if (installed) return
        synchronized(installLock) {
            if (installed) return
            installed = true

            val mainHandler = Handler(Looper.getMainLooper())
            val workerThread = HandlerThread("main-thread-stall-watchdog").apply { start() }
            val workerHandler = Handler(workerThread.looper)
            val state = MainThreadStallState(STALL_THRESHOLD_NANOS)

            lastMainHeartbeatNanos.set(SystemClock.elapsedRealtimeNanos())

            val heartbeat = object : Runnable {
                override fun run() {
                    lastMainHeartbeatNanos.set(SystemClock.elapsedRealtimeNanos())
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MILLIS)
                }
            }
            val sampler = object : Runnable {
                override fun run() {
                    val now = SystemClock.elapsedRealtimeNanos()
                    val heartbeatAt = lastMainHeartbeatNanos.get()
                    val delay = (now - heartbeatAt).coerceAtLeast(0L)
                    state.sample(delay)?.let { observation ->
                        Diagnostics.event(
                            category = DiagnosticCategory.UI,
                            name = "main_thread_stall",
                            level = DiagnosticLevel.WARN,
                            durationNanos = observation.durationNanos,
                            metadata = mapOf("severity" to observation.severity.wireName) +
                                RuntimeUiStallCorrelation.metadata(now),
                        )
                    }
                    workerHandler.postDelayed(this, HEARTBEAT_INTERVAL_MILLIS)
                }
            }

            mainHandler.post(heartbeat)
            workerHandler.postDelayed(sampler, HEARTBEAT_INTERVAL_MILLIS)
        }
    }
}
