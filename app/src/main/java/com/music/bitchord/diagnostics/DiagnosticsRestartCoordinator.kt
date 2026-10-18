package com.music.bitchord.diagnostics

internal class DiagnosticsRestartCoordinator(
    private val persistCapture: () -> Boolean,
    private val scheduleRestart: () -> Unit,
    private val terminateProcess: () -> Unit,
) {
    fun restartAndCapture(): Boolean {
        if (!persistCapture()) return false

        scheduleRestart()
        terminateProcess()
        return true
    }
}
