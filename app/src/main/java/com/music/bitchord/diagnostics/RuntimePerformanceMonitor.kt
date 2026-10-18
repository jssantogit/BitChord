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
import com.music.bitchord.ui.performance.RuntimePerformanceUiContext
import com.music.bitchord.ui.performance.RuntimePerformanceUiSignal
import com.music.bitchord.ui.performance.RuntimePerformanceUiStateKey
import com.music.bitchord.ui.performance.RuntimePerformanceUiStateValue
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
 * Runtime frame collector used only while an explicit diagnostics capture is
 * active.
 *
 * The main activity is the base surface. Modal surfaces such as Now Playing may
 * live in a separate Android [Window]; when one is visible, sampling moves to
 * that window instead of counting both it and the obscured activity. Android
 * delivers [FrameMetrics] on a dedicated handler thread, where frames are
 * reduced into bounded aggregate windows. Nothing is persisted per frame.
 *
 * Compose churn and feature state use a parallel bounded activity window. UI
 * callbacks only increment fixed in-memory counters; the same ten-second/context
 * boundaries turn those counters into diagnostics events.
 */
internal object RuntimePerformanceMonitor : RuntimePerformanceUiContext.Sink {
    private val worker = HandlerThread("runtime-performance").apply { start() }
    private val handler = Handler(worker.looper)
    private val lock = Any()
    private val surfaces = RuntimePerformanceSurfaceStack("activity_start")

    @Volatile
    private var installed = false

    @Volatile
    private var refreshRateHz: Float = DEFAULT_REFRESH_RATE_HZ

    @Volatile
    private var frameBudgetNanos: Long = frameBudgetNanosForRefreshRate(DEFAULT_REFRESH_RATE_HZ)

    private val session = RuntimePerformanceSession(surfaces.currentContext) { snapshot ->
        Diagnostics.event(
            category = DiagnosticCategory.UI,
            name = "runtime_frame_window",
            metadata = snapshot.toDiagnosticMetadata() + mapOf(
                "refreshRateHz" to String.format(Locale.US, "%.1f", refreshRateHz),
                "frameBudgetMs" to String.format(Locale.US, "%.2f", frameBudgetNanos / 1_000_000.0),
            ),
        )
    }

    private val uiActivityWindow = RuntimePerformanceUiActivityWindow(surfaces.currentContext) { snapshot ->
        Diagnostics.event(
            category = DiagnosticCategory.UI,
            name = "runtime_ui_activity_window",
            metadata = snapshot.toDiagnosticMetadata(),
        )
    }

    private var baseWindow: Window? = null
    private var overlayWindow: Window? = null
    private var sampledWindow: Window? = null
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
            // One aggregate must never mix two frame budgets on LTPO/high-refresh
            // devices. The refresh-rate transition is a natural frame boundary.
            session.flush()
            updateDisplayBudget(displayManager?.getDisplay(changedDisplayId)?.refreshRate)
        }
    }

    private val periodicFlush = object : Runnable {
        override fun run() {
            val captureActive = Diagnostics.captureStatus().active
            session.setCaptureActive(captureActive)
            uiActivityWindow.setCaptureActive(captureActive)
            synchronized(lock) {
                if (!captureActive) {
                    stopSamplingLocked()
                    return
                }
                session.flush()
                uiActivityWindow.flush()
                handler.postDelayed(this, FLUSH_INTERVAL_MILLIS)
            }
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
            displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
            RuntimePerformanceUiContext.install(this)
            installed = true
        }
    }

    /** Called from the activity lifecycle. Safe to call repeatedly for the same window. */
    fun attach(activity: Activity) {
        if (!installed) return
        synchronized(lock) {
            baseWindow = activity.window
            refreshSamplingLocked()
        }
    }

    /** Flushes the visible runtime surface before the main activity leaves the foreground. */
    fun detach(activity: Activity) {
        if (!installed) return
        synchronized(lock) {
            if (baseWindow !== activity.window) return
            session.flush()
            uiActivityWindow.flush()
            stopSamplingLocked()
            baseWindow = null
            overlayWindow = null
            surfaces.hideOverlay()
            displayId = null
        }
    }

    /**
     * Gives a modal window ownership of runtime measurements. Only this window
     * is sampled until [detachOverlayWindow] restores the latest base surface.
     */
    fun attachOverlayWindow(window: Window?, context: String) {
        if (!installed || window == null) return
        synchronized(lock) {
            overlayWindow = window
            surfaces.showOverlay(context)
            updateCurrentContextLocked()
            refreshSamplingLocked()
        }
    }

    fun detachOverlayWindow(window: Window?) {
        if (!installed || window == null) return
        synchronized(lock) {
            if (overlayWindow !== window) return
            overlayWindow = null
            surfaces.hideOverlay()
            updateCurrentContextLocked()
            refreshSamplingLocked()
        }
    }

    /**
     * Screen/interaction changes split the current aggregates without creating a
     * per-frame log stream. While an overlay is visible the base context is only
     * remembered; it becomes active again when that overlay closes.
     */
    fun setContext(context: String) {
        if (!installed) return
        synchronized(lock) {
            surfaces.setBaseContext(context)
            updateCurrentContextLocked()
        }
    }

    /**
     * Scoped Compose/navigation surfaces may overlap briefly during transitions.
     * The newest owner is visible; older owners can keep updating in place and
     * are restored with their latest state when the top owner leaves.
     */
    fun enterBaseSurface(owner: Any, context: String) {
        if (!installed) return
        synchronized(lock) {
            surfaces.enterBaseSurface(owner, context)
            updateCurrentContextLocked()
        }
    }

    fun updateBaseSurface(owner: Any, context: String) {
        if (!installed) return
        synchronized(lock) {
            surfaces.updateBaseSurface(owner, context)
            updateCurrentContextLocked()
        }
    }

    fun leaveBaseSurface(owner: Any) {
        if (!installed) return
        synchronized(lock) {
            surfaces.leaveBaseSurface(owner)
            updateCurrentContextLocked()
        }
    }

    override fun enter(owner: Any, context: String) = enterBaseSurface(owner, context)

    override fun update(owner: Any, context: String) = updateBaseSurface(owner, context)

    override fun leave(owner: Any) = leaveBaseSurface(owner)

    override fun record(signal: RuntimePerformanceUiSignal) {
        if (!installed) return
        uiActivityWindow.record(signal)
    }

    override fun setState(
        key: RuntimePerformanceUiStateKey,
        value: RuntimePerformanceUiStateValue,
    ) {
        if (!installed) return
        uiActivityWindow.setState(key, value)
    }

    private fun updateCurrentContextLocked() {
        val context = surfaces.currentContext
        session.setContext(context)
        uiActivityWindow.setContext(context)
    }

    private fun refreshSamplingLocked() {
        val captureActive = Diagnostics.captureStatus().active
        session.setCaptureActive(captureActive)
        uiActivityWindow.setCaptureActive(captureActive)
        if (!captureActive) {
            stopSamplingLocked()
            return
        }
        startSamplingLocked()
    }

    private fun startSamplingLocked() {
        val desiredWindow = overlayWindow ?: baseWindow ?: return
        switchSampledWindowLocked(desiredWindow)
        if (!displayListenerRegistered) {
            displayManager?.registerDisplayListener(displayListener, handler)
            displayListenerRegistered = displayManager != null
        }
        handler.removeCallbacks(periodicFlush)
        handler.postDelayed(periodicFlush, FLUSH_INTERVAL_MILLIS)
    }

    private fun switchSampledWindowLocked(window: Window) {
        if (sampledWindow === window && frameListenerRegistered) return

        session.flush()
        uiActivityWindow.flush()
        if (frameListenerRegistered) {
            sampledWindow?.removeOnFrameMetricsAvailableListener(frameListener)
            frameListenerRegistered = false
        }

        sampledWindow = window
        displayId = window.decorView.display?.displayId
        updateDisplayBudget(displayId?.let { displayManager?.getDisplay(it)?.refreshRate })
        window.addOnFrameMetricsAvailableListener(frameListener, handler)
        frameListenerRegistered = true
    }

    private fun stopSamplingLocked() {
        handler.removeCallbacks(periodicFlush)
        if (frameListenerRegistered) {
            sampledWindow?.removeOnFrameMetricsAvailableListener(frameListener)
            frameListenerRegistered = false
        }
        sampledWindow = null
        if (displayListenerRegistered) {
            displayManager?.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
    }

    private fun updateDisplayBudget(rate: Float?) {
        val validRate = rate?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_REFRESH_RATE_HZ
        refreshRateHz = validRate
        frameBudgetNanos = frameBudgetNanosForRefreshRate(validRate)
    }
}
