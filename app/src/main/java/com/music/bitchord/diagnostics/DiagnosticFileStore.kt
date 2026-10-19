package com.music.bitchord.diagnostics

import java.io.File
import java.io.FileOutputStream

/**
 * Bounded on-device storage for an explicitly enabled diagnostic capture.
 *
 * Only two capture segments are retained: the active file and the immediately
 * previous segment. Crash and export files are separate so a crash survives a
 * capture rotation and an export is stable while Android's sharesheet reads it.
 */
class DiagnosticFileStore(
    private val rootDir: File,
    private val maxCaptureBytes: Long,
) {
    init {
        require(maxCaptureBytes > 0L) { "maxCaptureBytes must be greater than zero" }
    }

    private val lock = Any()
    private var rotationsSinceOpen = 0L

    /** Observable known rotation count since store construction, not across restarts. */
    val captureRotationsSinceOpen: Long
        get() = synchronized(lock) { rotationsSinceOpen }

    fun appendCapture(line: String) {
        synchronized(lock) {
            ensureRoot()
            val current = currentCaptureFile()
            val bytes = boundedLineBytes(line)
            if (current.exists() && current.length() + bytes.size > maxCaptureBytes) {
                rotateCaptureLocked()
            }
            FileOutputStream(current, true).use { output -> output.write(bytes) }
        }
    }

    fun writeLastCrash(contents: String) {
        synchronized(lock) {
            ensureRoot()
            val target = lastCrashFile()
            val temp = rootDir.resolve("last-crash.tmp")
            temp.writeText(contents)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    fun createExport(
        metadataLines: List<String>,
        recentEventLines: List<String>,
        fileName: String,
        reportV2Input: DiagnosticReportV2Input? = null,
        includeJsonl: Boolean = false,
    ): File = synchronized(lock) {
        ensureRoot()
        val exports = exportsDir().apply { mkdirs() }
        val export = exports.resolve(safeFileName(fileName))
        export.bufferedWriter().use { writer ->
            writer.appendLine("BitChord diagnostics report")
            writer.appendLine()
            writer.appendLine("[metadata]")
            metadataLines.forEach { writer.appendLine(safeSharedText(it)) }
            writer.appendLine()
            writer.appendLine("[recent-events]")
            recentEventLines.forEach { writer.appendLine(safeSharedText(it)) }

            val capture = captureFilesLocked()
            val capturedLines = ArrayList<String>()
            writer.appendLine()
            writer.appendLine("[persisted-capture]")
            if (capture.isEmpty()) {
                writer.appendLine("<none>")
            } else {
                capture.forEach { file ->
                    writer.appendLine("--- ${file.name} ---")
                    file.forEachLine { raw ->
                        // Old persisted logs may predate D1's ingress sanitization.
                        val safe = safeSharedText(raw)
                        writer.appendLine(safe)
                        if (reportV2Input != null) capturedLines.add(safe)
                    }
                }
            }

            writer.appendLine()
            writer.appendLine("[last-crash]")
            val crash = lastCrashFile()
            if (crash.isFile) {
                crash.forEachLine { writer.appendLine(safeSharedText(it)) }
            } else {
                writer.appendLine("<none>")
            }

            // Existing TXT section layout remains intact; v2 is an additive
            // deterministic, bounded audit view of the same locked file snapshot.
            reportV2Input?.let { input ->
                val report = DiagnosticReportV2.build(input, capturedLines)
                writer.appendLine()
                writer.appendLine("[report-v2]")
                writer.append(report.toHumanText())
                if (includeJsonl) {
                    writer.appendLine()
                    writer.appendLine("[events-jsonl-v2]")
                    writer.append(report.toJsonl())
                }
            }
        }
        pruneExportsLocked()
        export
    }

    fun clear() {
        synchronized(lock) {
            if (!rootDir.exists()) return
            rootDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    fun captureFiles(): List<File> = synchronized(lock) { captureFilesLocked() }

    fun exportFiles(): List<File> = synchronized(lock) {
        exportsDir().listFiles()?.filter(File::isFile)?.sortedBy(File::getName).orEmpty()
    }

    fun currentCaptureFile(): File = rootDir.resolve("capture.log")

    fun lastCrashFile(): File = rootDir.resolve("last-crash.log")

    private fun previousCaptureFile(): File = rootDir.resolve("capture.previous.log")

    private fun exportsDir(): File = rootDir.resolve("exports")

    private fun ensureRoot() {
        if (!rootDir.exists() && !rootDir.mkdirs() && !rootDir.isDirectory) {
            error("Unable to create diagnostics directory: ${rootDir.absolutePath}")
        }
    }

    private fun rotateCaptureLocked() {
        val current = currentCaptureFile()
        if (!current.exists()) return
        rotationsSinceOpen++
        val previous = previousCaptureFile()
        if (previous.exists()) previous.delete()
        if (!current.renameTo(previous)) {
            current.copyTo(previous, overwrite = true)
            current.delete()
        }
    }

    private fun captureFilesLocked(): List<File> =
        listOf(previousCaptureFile(), currentCaptureFile()).filter(File::isFile)

    private fun pruneExportsLocked() {
        val reports = exportsDir().listFiles()?.filter(File::isFile).orEmpty()
        reports
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .dropLast(MAX_EXPORT_FILES)
            .forEach(File::delete)
    }

    private fun boundedLineBytes(line: String): ByteArray {
        val terminated = if (line.endsWith('\n')) line else "$line\n"
        val bytes = terminated.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxCaptureBytes) return bytes
        return bytes.copyOf(maxCaptureBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * Last outbound boundary, including legacy on-disk content recorded before D1.
     * This intentionally favors privacy over preserving historic unsalted UUID
     * trace identifiers; current causal flowId/parentSpanId use safe ref_ tokens.
     */
    private fun safeSharedText(line: String): String =
        DiagnosticSanitizer.freeText(line)
            .replace("\r", "\\r")
            .replace("\n", "\\n")

    private fun safeFileName(requested: String): String {
        val sanitized = requested
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.')
        return sanitized.ifBlank { "diagnostics.txt" }
    }

    private companion object {
        const val MAX_EXPORT_FILES = 3
    }
}
