package com.music.bitchord.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Process-wide diagnostics for BitChord.
 *
 * Recording an event is intentionally cheap: sanitize, append to a bounded
 * in-memory buffer, and (on debug builds) emit to logcat. Disk persistence is
 * opt-in and always dispatched to a single bounded writer queue. The one
 * exception is the uncaught-exception path, where the latest bounded snapshot
 * is written synchronously before Android terminates the process.
 */
object Diagnostics {
    private const val LOG_TAG = "BitChordDiag"
    private const val PREFS_NAME = "bitchord_diagnostics"
    private const val KEY_CAPTURE_EXPIRES_AT = "capture_expires_at"
    private const val BUFFER_CAPACITY = 1_000
    private const val WRITER_QUEUE_CAPACITY = 512
    private const val MAX_CAPTURE_BYTES = 2L * 1024L * 1024L
    private const val MAX_ERROR_CHARS = 32_000
    private const val MAX_CRASH_EVENTS = 200
    private const val DEFAULT_CAPTURE_MILLIS = 60L * 60L * 1_000L

    data class CaptureStatus(
        val active: Boolean,
        val expiresAtEpochMillis: Long?,
        val remainingMillis: Long,
    )

    private val sessionId: String = UUID.randomUUID().toString().substring(0, 8)
    private val buffer = DiagnosticBuffer(BUFFER_CAPACITY)
    private val droppedWrites = AtomicLong(0L)
    private val installLock = Any()
    private val rejectionHandler = RejectedExecutionHandler { _, _ -> droppedWrites.incrementAndGet() }
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WRITER_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "diagnostics-writer").apply { isDaemon = true } },
        rejectionHandler,
    )

    @Volatile
    private var application: Application? = null

    @Volatile
    private var fileStore: DiagnosticFileStore? = null

    @Volatile
    private var captureWindow: CaptureWindow? = null

    @Volatile
    private var installed = false

    @Volatile
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null

    fun install(app: Application) {
        if (installed) return
        synchronized(installLock) {
            if (installed) return
            val started = SystemClock.elapsedRealtimeNanos()
            application = app
            fileStore = DiagnosticFileStore(
                rootDir = File(app.filesDir, "diagnostics"),
                maxCaptureBytes = MAX_CAPTURE_BYTES,
            )
            restoreCaptureWindow(app)
            installCrashHandler()
            installed = true
            event(
                category = DiagnosticCategory.STARTUP,
                name = "diagnostics_install",
                durationNanos = SystemClock.elapsedRealtimeNanos() - started,
                metadata = mapOf("captureActive" to captureStatus().active.toString()),
            )
        }
    }

    fun event(
        category: DiagnosticCategory,
        name: String,
        level: DiagnosticLevel = DiagnosticLevel.INFO,
        message: String? = null,
        metadata: Map<String, String> = emptyMap(),
        error: Throwable? = null,
        durationNanos: Long? = null,
    ) {
        val event = DiagnosticEvent(
            epochMillis = System.currentTimeMillis(),
            monotonicNanos = SystemClock.elapsedRealtimeNanos(),
            sessionId = sessionId,
            level = level,
            category = category,
            name = DiagnosticSanitizer.text(name),
            message = message?.let(DiagnosticSanitizer::text),
            threadName = Thread.currentThread().name,
            durationNanos = durationNanos,
            metadata = DiagnosticSanitizer.metadata(metadata),
            error = error?.let(::safeThrowableText),
        )
        record(event)
    }

    fun <T> span(
        category: DiagnosticCategory,
        name: String,
        metadata: Map<String, String> = emptyMap(),
        block: () -> T,
    ): T {
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            val result = block()
            event(
                category = category,
                name = name,
                metadata = metadata,
                durationNanos = SystemClock.elapsedRealtimeNanos() - started,
            )
            result
        } catch (error: Throwable) {
            event(
                category = category,
                name = name,
                level = DiagnosticLevel.ERROR,
                metadata = metadata,
                error = error,
                durationNanos = SystemClock.elapsedRealtimeNanos() - started,
            )
            throw error
        }
    }

    /** Preserve the shared DebugLog contract while routing Android diagnostics through one sink. */
    fun fromDebugLog(level: Char, tag: String, message: String, error: Throwable?) {
        event(
            category = categoryForTag(tag),
            name = "debug_log:$tag",
            level = when (level) {
                'D' -> DiagnosticLevel.DEBUG
                'I' -> DiagnosticLevel.INFO
                'W' -> DiagnosticLevel.WARN
                else -> DiagnosticLevel.ERROR
            },
            message = message,
            error = error,
        )
    }

    fun startCapture(durationMillis: Long = DEFAULT_CAPTURE_MILLIS): CaptureStatus {
        require(durationMillis > 0L) { "durationMillis must be greater than zero" }
        val app = checkNotNull(application) { "Diagnostics.install must be called first" }
        val expiresAt = System.currentTimeMillis() + durationMillis
        captureWindow = CaptureWindow(expiresAt)
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CAPTURE_EXPIRES_AT, expiresAt)
            .apply()
        event(
            category = DiagnosticCategory.APP,
            name = "capture_started",
            metadata = mapOf("durationMillis" to durationMillis.toString()),
        )
        return captureStatus()
    }

    fun stopCapture(): CaptureStatus {
        val app = checkNotNull(application) { "Diagnostics.install must be called first" }
        if (isCaptureActive()) {
            event(category = DiagnosticCategory.APP, name = "capture_stopped")
        }
        captureWindow = null
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CAPTURE_EXPIRES_AT)
            .apply()
        return captureStatus()
    }

    fun captureStatus(nowEpochMillis: Long = System.currentTimeMillis()): CaptureStatus {
        val window = captureWindow
        if (window == null) return CaptureStatus(false, null, 0L)
        if (!window.isActive(nowEpochMillis)) {
            expireCaptureWindow()
            return CaptureStatus(false, null, 0L)
        }
        return CaptureStatus(
            active = true,
            expiresAtEpochMillis = window.expiresAtEpochMillis,
            remainingMillis = max(0L, window.expiresAtEpochMillis - nowEpochMillis),
        )
    }

    /** Clears memory, capture segments, exports and retained crash. Capture mode itself stays enabled. */
    fun clear() {
        buffer.clear()
        writer.execute { fileStore?.clear() }
        event(category = DiagnosticCategory.APP, name = "diagnostics_cleared")
    }

    fun recentEvents(): List<DiagnosticEvent> = buffer.snapshot()

    fun hasLastCrash(): Boolean = fileStore?.lastCrashFile()?.isFile == true

    /**
     * Creates a stable report file. Call from a background dispatcher when used
     * by UI; this waits briefly for already queued capture writes to drain.
     */
    fun createExport(): File {
        val store = checkNotNull(fileStore) { "Diagnostics.install must be called first" }
        flushWriter(timeoutMillis = 2_000L)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return store.createExport(
            metadataLines = reportMetadataLines(),
            recentEventLines = buffer.snapshot().map(DiagnosticFormatter::format),
            fileName = "bitchord-diagnostics-$timestamp.txt",
        )
    }

    private fun record(event: DiagnosticEvent) {
        buffer.append(event)
        val line = DiagnosticFormatter.format(event)
        if (BuildConfig.DEBUG) logcat(event.level, line)
        if (isCaptureActive(event.epochMillis)) {
            writer.execute { fileStore?.appendCapture(line) }
        }
    }

    private fun restoreCaptureWindow(app: Application) {
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val expiresAt = prefs.getLong(KEY_CAPTURE_EXPIRES_AT, 0L)
        if (expiresAt > System.currentTimeMillis()) {
            captureWindow = CaptureWindow(expiresAt)
        } else if (expiresAt != 0L) {
            prefs.edit().remove(KEY_CAPTURE_EXPIRES_AT).apply()
        }
    }

    private fun isCaptureActive(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        val window = captureWindow ?: return false
        if (window.isActive(nowEpochMillis)) return true
        expireCaptureWindow()
        return false
    }

    private fun expireCaptureWindow() {
        captureWindow = null
        application
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(KEY_CAPTURE_EXPIRES_AT)
            ?.apply()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousCrashHandler = previous
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashEvent = DiagnosticEvent(
                    epochMillis = System.currentTimeMillis(),
                    monotonicNanos = SystemClock.elapsedRealtimeNanos(),
                    sessionId = sessionId,
                    level = DiagnosticLevel.ERROR,
                    category = DiagnosticCategory.APP,
                    name = "uncaught_exception",
                    message = null,
                    threadName = thread.name,
                    durationNanos = null,
                    metadata = emptyMap(),
                    error = safeThrowableText(throwable),
                )
                buffer.append(crashEvent)
                val snapshot = buffer.snapshot().takeLast(MAX_CRASH_EVENTS)
                val crashText = buildString {
                    appendLine("BitChord retained crash")
                    reportMetadataLines().forEach(::appendLine)
                    appendLine()
                    appendLine("[events]")
                    snapshot.forEach { appendLine(DiagnosticFormatter.format(it)) }
                }
                fileStore?.writeLastCrash(crashText)
            } catch (diagnosticsFailure: Throwable) {
                Log.e(LOG_TAG, "Failed to retain crash diagnostics", diagnosticsFailure)
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    private fun flushWriter(timeoutMillis: Long) {
        val latch = CountDownLatch(1)
        try {
            writer.execute { latch.countDown() }
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun reportMetadataLines(): List<String> {
        val status = captureStatus()
        return listOf(
            "package=${BuildConfig.APPLICATION_ID}",
            "versionName=${BuildConfig.VERSION_NAME}",
            "versionCode=${BuildConfig.VERSION_CODE}",
            "buildType=${BuildConfig.BUILD_TYPE}",
            "flavor=${BuildConfig.FLAVOR}",
            "sdk=${Build.VERSION.SDK_INT}",
            "device=${DiagnosticSanitizer.text("${Build.MANUFACTURER} ${Build.MODEL}")}",
            "session=$sessionId",
            "captureActive=${status.active}",
            "captureExpiresAt=${status.expiresAtEpochMillis ?: 0L}",
            "droppedPersistenceWrites=${droppedWrites.get()}",
        )
    }

    private fun safeThrowableText(error: Throwable): String =
        DiagnosticSanitizer.text(error.stackTraceToString().take(MAX_ERROR_CHARS))

    private fun categoryForTag(tag: String): DiagnosticCategory {
        val normalized = tag.lowercase(Locale.ROOT)
        return when {
            "play" in normalized || "audio" in normalized -> DiagnosticCategory.PLAYER
            "http" in normalized || "network" in normalized || "innertube" in normalized -> DiagnosticCategory.NETWORK
            "catalog" in normalized || "source" in normalized || "media" in normalized -> DiagnosticCategory.CATALOG
            "room" in normalized || "database" in normalized || normalized.endsWith("db") -> DiagnosticCategory.DATABASE
            "ui" in normalized || "screen" in normalized -> DiagnosticCategory.UI
            else -> DiagnosticCategory.OTHER
        }
    }

    private fun logcat(level: DiagnosticLevel, line: String) {
        when (level) {
            DiagnosticLevel.DEBUG -> Log.d(LOG_TAG, line)
            DiagnosticLevel.INFO -> Log.i(LOG_TAG, line)
            DiagnosticLevel.WARN -> Log.w(LOG_TAG, line)
            DiagnosticLevel.ERROR -> Log.e(LOG_TAG, line)
        }
    }
}
