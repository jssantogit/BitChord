package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticFileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `capture rotates and remains bounded to current plus previous file`() {
        val root = temporaryFolder.newFolder("diagnostics")
        val store = DiagnosticFileStore(rootDir = root, maxCaptureBytes = 24L)

        store.appendCapture("first-123456")
        store.appendCapture("second-12345")
        store.appendCapture("third-123456")

        val files = store.captureFiles()
        assertEquals(2, files.size)
        assertTrue(files.any { it.name == "capture.log" })
        assertTrue(files.any { it.name == "capture.previous.log" })
        assertTrue(files.sumOf { it.length() } <= 48L)
        assertTrue(store.currentCaptureFile().readText().contains("third-123456"))
    }

    @Test
    fun `oversized single line is truncated before writing`() {
        val root = temporaryFolder.newFolder("diagnostics")
        val store = DiagnosticFileStore(rootDir = root, maxCaptureBytes = 12L)

        store.appendCapture("abcdefghijklmnopqrstuvwxyz")

        assertTrue(store.currentCaptureFile().length() <= 12L)
    }

    @Test
    fun `last crash replaces the previous crash atomically`() {
        val root = temporaryFolder.newFolder("diagnostics")
        val store = DiagnosticFileStore(rootDir = root, maxCaptureBytes = 128L)

        store.writeLastCrash("first crash")
        store.writeLastCrash("second crash")

        assertEquals("second crash", store.lastCrashFile().readText())
        assertFalse(root.resolve("last-crash.tmp").exists())
    }

    @Test
    fun `export contains metadata recent events capture and crash`() {
        val root = temporaryFolder.newFolder("diagnostics")
        val store = DiagnosticFileStore(rootDir = root, maxCaptureBytes = 256L)
        store.appendCapture("persisted event")
        store.writeLastCrash("crash snapshot")

        val export = store.createExport(
            metadataLines = listOf("app=BitChord", "version=test"),
            recentEventLines = listOf("recent event"),
            fileName = "report.txt",
        )
        val text = export.readText()

        assertTrue(text.contains("app=BitChord"))
        assertTrue(text.contains("version=test"))
        assertTrue(text.contains("recent event"))
        assertTrue(text.contains("persisted event"))
        assertTrue(text.contains("crash snapshot"))
    }

    @Test
    fun `clear removes persisted diagnostics and exports`() {
        val root = temporaryFolder.newFolder("diagnostics")
        val store = DiagnosticFileStore(rootDir = root, maxCaptureBytes = 256L)
        store.appendCapture("persisted event")
        store.writeLastCrash("crash snapshot")
        store.createExport(emptyList(), emptyList(), "report.txt")

        store.clear()

        assertTrue(store.captureFiles().isEmpty())
        assertFalse(store.lastCrashFile().exists())
        assertTrue(store.exportFiles().isEmpty())
    }
}
