package com.music.bitchord.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.Locale
import kotlin.math.roundToLong

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val DEFAULT_REFRESH_RATE_HZ = 60f
private const val FLUSH_INTERVAL_MILLIS = 10_000L
private const val MAIN_ACTIVITY_CLASS = "com.music.bitchord.MainActivity"

internal fun frameBudgetNanosForRefreshRate(refreshRateHz: Float): Long {
    val validRate = refreshRateHz.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_REFRESH_RATE_HZ
    return (NANOS_PER_SECOND / validRate.toDouble()).roundToLong()
}

/**
 * Main-activity frame collector used only while an explicit diagnostics capture
 * is active.
 *
 * Android delivers [FrameMetrics] on a dedicated handler thread, where frames
 * are reduced into [RuntimePerformanceSession]. Nothing is persisted per frame:
 * context switches, activity stops and a short periodic boundary emit one
 * bounded aggregate event instead. When capture is off the window listener is
 * not installed at all.
 */
internal object RuntimePerformanceMonitor {
    private val worker = HandlerThread("runtime-performance").apply { start() }
    private val handler = Handler(worker.looper)
    private val lock = Any()

    @Volatile
    private var installed = false

    @Volatile
    private var refreshRateHz: Float = DEFAULT_REFRESH_RATE_HZ

    @Volatile
    private var frameBudgetNanos: Long = frameBudgetNanosForRefreshRate(DEFAULT_REFRESH_RATE_HZ)

    private val session = RuntimePerformanceSession("activity_start") { snapshot ->
        Diagnostics.event(
            category = DiagnosticCategory.UI,
            name = "runtime_frame_window",
            metadata = snapshot.toDiagnosticMetadata() + mapOf(
                "refreshRateHz" to String.format(Locale.US, "%.1f", refreshRateHz),
                "frameBudgetMs" to String.format(Locale.US, "%.2f", frameBudgetNanos / 1_000_000.0),
            ),
        )
    }

    private var attachedWindow: Window? = null
    private var displayManager: DisplayManager? = null
    private var displayId: Int? = null
    private var frameListenerRegistered = false
    private var displayListenerRegistered = false

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val durationNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (durationNanos > 0L) {
            session.recordFrame(durationNanos, frameBudgetNanos)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(changedDisplayId: Int) {
            if (changedDisplayId != displayId) return
            // Keep each aggregate window on one budget. A refresh-rate switch is
            // itself a natural measurement boundary on LTPO/high-refresh devices.
            session.flush()
            updateDisplayBudget(displayManager?.getDisplay(changedDisplayId)?.refreshRate)
        }
    }

    private val periodicFlush = object : Runnable {
        override fun run() {
            val captureActive = Diagnostics.captureStatus().active
            session.setCaptureActive(captureActive)
            if (!captureActive) {
                synchronized(lock) { stopSamplingLocked() }
                return
            }
            session.flush()
            handler.postDelayed(this, FLUSH_INTERVAL_MILLIS)
        }
    }

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit

        override fun onActivityStarted(activity: Activity) {
            if (activity.javaClass.name == MAIN_ACTIVITY_CLASS) attach(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity.javaClass.name == MAIN_ACTIVITY_CLASS) detach(activity)
        }
    }

    /** Installs one process-wide lifecycle hook; no frame listener exists until capture is active. */
    fun install(app: Application) {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
            installed = true
        }
    }

    /** Called from the activity lifecycle. Safe to call repeatedly for the same window. */
    fun attach(activity: Activity) {
        synchronized(lock) {
            val window = activity.window
            if (attachedWindow !== window) {
                detachLocked()
                attachedWindow = window
                displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                displayId = window.decorView.display?.displayId
                updateDisplayBudget(displayId?.let { displayManager?.getDisplay(it)?.refreshRate })
            }

            val captureActive = Diagnostics.captureStatus().active
            session.setCaptureActive(captureActive)
            if (captureActive) startSamplingLocked()
        }
    }

    /** Flushes the visible activity window before it leaves the foreground. */
    fun detach(activity: Activity) {
        synchronized(lock) {
            if (attachedWindow !== activity.window) return
            detachLocked()
        }
    }

    /**
     * Screen/interaction changes split the current aggregate without creating a
     * per-frame log stream. Context can be updated even while capture is off.
     */
    fun setContext(context: String) {
        session.setContext(context)
    }

    private fun startSamplingLocked() {
        val window = attachedWindow ?: return
        if (!frameListenerRegistered) {
            window.addOnFrameMetricsAvailableListener(frameListener, handler)
            frameListenerRegistered = true
        }
        if (!displayListenerRegistered) {
            displayManager?.registerDisplayListener(displayListener, handler)
            displayListenerRegistered = displayManager != null
        }
        handler.removeCallbacks(periodicFlush)
        handler.postDelayed(periodicFlush, FLUSH_INTERVAL_MILLIS)
    }

    private fun stopSamplingLocked() {
        handler.removeCallbacks(periodicFlush)
        if (frameListenerRegistered) {
            attachedWindow?.removeOnFrameMetricsAvailableListener(frameListener)
            frameListenerRegistered = false
        }
        if (displayListenerRegistered) {
            displayManager?.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
    }

    private fun detachLocked() {
        session.flush()
        stopSamplingLocked()
        attachedWindow = null
        displayManager = null
        displayId = null
    }

    private fun updateDisplayBudget(rate: Float?) {
        val validRate = rate?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_REFRESH_RATE_HZ
        refreshRateHz = validRate
        frameBudgetNanos = frameBudgetNanosForRefreshRate(validRate)
    }
}
