package com.music.bitchord.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.BuildConfig
import com.music.bitchord.observability.SessionTraceReferenceEncoder
import com.music.bitchord.observability.StructuredTracer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
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
    private const val KEY_DIAGNOSTIC_MODE = "diagnostic_mode"
    private const val BUFFER_CAPACITY = 1_000
    private const val WRITER_QUEUE_CAPACITY = 512
    private const val MAX_CAPTURE_BYTES = 2L * 1024L * 1024L
    private const val MAX_ERROR_CHARS = DiagnosticSanitizer.MAX_TEXT_CHARS
    private const val MAX_CRASH_EVENTS = 200
    private const val MAX_CRASH_CHARS = 512_000
    private const val DEFAULT_CAPTURE_MILLIS = 60L * 60L * 1_000L

    data class CaptureStatus(
        val active: Boolean,
        val expiresAtEpochMillis: Long?,
        val remainingMillis: Long,
    )

    private val sessionId: String = UUID.randomUUID().toString().substring(0, 8)
    private val traceReferenceSalt: String = UUID.randomUUID().toString()
    private val buffer = DiagnosticBuffer(BUFFER_CAPACITY)
    private val installLock = Any()
    private val writer = DiagnosticWriteQueue(WRITER_QUEUE_CAPACITY)
    private val structuredTracerInstance: StructuredTracer by lazy {
        StructuredTracer(
            mode = diagnosticMode.toTraceMode(),
            sink = DiagnosticsStructuredTraceSink,
            referenceEncoder = SessionTraceReferenceEncoder(traceReferenceSalt),
        )
    }

    @Volatile
    private var application: Application? = null

    @Volatile
    private var fileStore: DiagnosticFileStore? = null

    @Volatile
    private var captureWindow: CaptureWindow? = null

    @Volatile
    private var diagnosticMode: DiagnosticMode = DiagnosticMode.STANDARD

    @Volatile
    private var installed = false

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
            restoreDiagnosticMode(app)
            restoreCaptureWindow(app)
            // Local-only, best-effort typed recovery events. Never blocks source work.
            DiagnosticFailureBreadcrumbs.sink = DiagnosticFailureSink { breadcrumb ->
                event(
                    category = breadcrumb.category,
                    name = "failure_recovery",
                    level = if (breadcrumb.outcome == DiagnosticRecoveryOutcome.RECOVERED) {
                        DiagnosticLevel.INFO
                    } else {
                        DiagnosticLevel.WARN
                    },
                    metadata = mapOf(
                        "reasonCode" to breadcrumb.reason.wireName,
                        "recoveryOutcome" to breadcrumb.outcome.wireName,
                    ),
                )
            }
            installCrashHandler()
            installed = true
            event(
                category = DiagnosticCategory.STARTUP,
                name = "diagnostics_install",
                durationNanos = SystemClock.elapsedRealtimeNanos() - started,
                metadata = mapOf(
                    "captureActive" to captureStatus().active.toString(),
                    "diagnosticMode" to diagnosticMode.name.lowercase(Locale.ROOT),
                ),
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
            name = DiagnosticSanitizer.text(name, 256),
            message = message?.let(DiagnosticSanitizer::freeText),
            threadName = DiagnosticSanitizer.text(Thread.currentThread().name, 128),
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

    /** Neutral structured tracer for feature/domain adapters. It never exposes Android types. */
    fun structuredTracer(): StructuredTracer = structuredTracerInstance.also {
        it.mode = diagnosticMode.toTraceMode()
    }

    fun diagnosticMode(): DiagnosticMode = diagnosticMode

    fun setDiagnosticMode(mode: DiagnosticMode): Boolean {
        val app = checkNotNull(application) { "Diagnostics.install must be called first" }
        val persisted = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DIAGNOSTIC_MODE, mode.name)
            .commit()
        if (!persisted) {
            event(
                category = DiagnosticCategory.APP,
                name = "diagnostic_mode_persist_failed",
                level = DiagnosticLevel.WARN,
                metadata = mapOf("requestedMode" to mode.name.lowercase(Locale.ROOT)),
            )
            return false
        }
        diagnosticMode = mode
        structuredTracerInstance.mode = mode.toTraceMode()
        event(
            category = DiagnosticCategory.APP,
            name = "diagnostic_mode_changed",
            metadata = mapOf("mode" to mode.name.lowercase(Locale.ROOT)),
        )
        return true
    }

    /** Preserve legacy DebugLog only at the detail allowed by the current diagnostic mode. */
    fun fromDebugLog(level: Char, tag: String, message: String, error: Throwable?) {
        val diagnosticLevel = when (level) {
            'D' -> DiagnosticLevel.DEBUG
            'I' -> DiagnosticLevel.INFO
            'W' -> DiagnosticLevel.WARN
            else -> DiagnosticLevel.ERROR
        }
        if (!diagnosticMode.allowsLegacy(diagnosticLevel)) return
        val link = com.music.bitchord.data.TrackLog.currentCorrelation()
        event(
            category = categoryForTag(tag),
            name = "debug_log:$tag",
            level = diagnosticLevel,
            message = message,
            metadata = link?.let { mapOf(
                "flowId" to it.flowId,
                "parentSpanId" to it.parentSpanId,
            ) } ?: emptyMap(),
            error = error,
        )
    }

    fun startCapture(durationMillis: Long = DEFAULT_CAPTURE_MILLIS): CaptureStatus {
        require(durationMillis > 0L) { "durationMillis must be greater than zero" }
        val app = checkNotNull(application) { "Diagnostics.install must be called first" }
        val expiresAt = System.currentTimeMillis() + durationMillis
        val persisted = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CAPTURE_EXPIRES_AT, expiresAt)
            .commit()
        if (!persisted) {
            event(
                category = DiagnosticCategory.APP,
                name = "capture_start_failed",
                level = DiagnosticLevel.WARN,
                message = "Could not persist capture expiry",
            )
            return CaptureStatus(active = false, expiresAtEpochMillis = null, remainingMillis = 0L)
        }
        captureWindow = CaptureWindow(expiresAt)
        event(
            category = DiagnosticCategory.APP,
            name = "capture_started",
            metadata = mapOf(
                "durationMillis" to durationMillis.toString(),
                "diagnosticMode" to diagnosticMode.name.lowercase(Locale.ROOT),
            ),
        )
        return captureStatus()
    }

    fun stopCapture(): CaptureStatus {
        val app = checkNotNull(application) { "Diagnostics.install must be called first" }
        if (isCaptureActive()) {
            event(category = DiagnosticCategory.APP, name = "capture_stopped")
        }
        captureWindow = null
        val removed = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CAPTURE_EXPIRES_AT)
            .commit()
        if (!removed) {
            event(
                category = DiagnosticCategory.APP,
                name = "capture_stop_persist_failed",
                level = DiagnosticLevel.WARN,
                message = "Could not remove persisted capture expiry",
            )
        }
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
        writer.runControl { fileStore?.clear() }
        event(category = DiagnosticCategory.APP, name = "diagnostics_cleared")
    }

    fun recentEvents(): List<DiagnosticEvent> = buffer.snapshot()

    fun hasLastCrash(): Boolean = fileStore?.lastCrashFile()?.isFile == true

    /**
     * Creates a stable report file. Call from a background dispatcher when used
     * by UI; this waits briefly for already issued capture writes to resolve.
     */
    fun createExport(): File {
        val store = checkNotNull(fileStore) { "Diagnostics.install must be called first" }
        val flushComplete = writer.awaitWritesIssuedBeforeCall(timeoutMillis = 2_000L)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val snapshot = buffer.snapshot()
        return store.createExport(
            metadataLines = reportMetadataLines() +
                "captureFlushStatus=${DiagnosticCaptureIntegrity.flushStatus(flushComplete)}",
            recentEventLines = snapshot.map(DiagnosticFormatter::format),
            fileName = "bitchord-diagnostics-$timestamp.txt",
            reportV2Input = DiagnosticReportV2Input(
                recentEvents = snapshot,
                flushComplete = flushComplete,
                rejectedWrites = writer.droppedWrites,
                evictedMemoryEvents = buffer.evictedCount,
                ioFailures = writer.failedWrites,
                captureRotations = store.captureRotationsSinceOpen,
            ),
        )
    }

    private fun record(event: DiagnosticEvent) {
        val numbered = buffer.append(event)
        val line = DiagnosticFormatter.format(numbered)
        if (BuildConfig.DEBUG) logcat(numbered.level, line)
        if (isCaptureActive(event.epochMillis)) {
            writer.offer { fileStore?.appendCapture(line) }
        }
    }

    private fun restoreDiagnosticMode(app: Application) {
        val stored = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DIAGNOSTIC_MODE, null)
        diagnosticMode = stored
            ?.let { runCatching { DiagnosticMode.valueOf(it) }.getOrNull() }
            ?: DiagnosticMode.STANDARD
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
                    threadName = DiagnosticSanitizer.text(thread.name, 128),
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
                }.take(MAX_CRASH_CHARS)
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
            "diagnosticMode=${diagnosticMode.name.lowercase(Locale.ROOT)}",
            "captureActive=${status.active}",
            "captureExpiresAt=${status.expiresAtEpochMillis ?: 0L}",
            "droppedPersistenceWrites=${writer.droppedWrites}",
        )
    }

    private fun safeThrowableText(error: Throwable): String =
        DiagnosticSanitizer.freeText(error.stackTraceToString(), MAX_ERROR_CHARS)

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
