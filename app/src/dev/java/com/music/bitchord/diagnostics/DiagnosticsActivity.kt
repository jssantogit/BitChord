package com.music.bitchord.diagnostics

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/** Developer-only control panel for collecting and sharing diagnostics without ADB. */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiagnosticsPanel(
                        onShare = ::shareDiagnostics,
                        onRestartAndCapture = ::restartAndCaptureStartup,
                    )
                }
            }
        }
    }

    private fun shareDiagnostics() {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { Diagnostics.createExport() }
            }
            result.onSuccess { report ->
                startActivity(
                    Intent.createChooser(
                        DiagnosticsShare.createIntent(this@DiagnosticsActivity, report),
                        "Share BitChord diagnostics",
                    ),
                )
            }.onFailure { error ->
                Toast.makeText(
                    this@DiagnosticsActivity,
                    "Could not create diagnostics report: ${error.message ?: "unknown error"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun restartAndCaptureStartup() {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        ?: error("App launch intent is unavailable")
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                    val restartIntent = PendingIntent.getActivity(
                        this@DiagnosticsActivity,
                        RESTART_REQUEST_CODE,
                        launchIntent,
                        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val alarmManager = getSystemService(AlarmManager::class.java)
                    val coordinator = DiagnosticsRestartCoordinator(
                        persistCapture = { Diagnostics.startCapture().active },
                        scheduleRestart = {
                            alarmManager.set(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS,
                                restartIntent,
                            )
                        },
                        terminateProcess = {
                            runOnUiThread {
                                finishAffinity()
                                Process.killProcess(Process.myPid())
                            }
                        },
                    )
                    check(coordinator.restartAndCapture()) {
                        "Could not persist diagnostics capture before restart"
                    }
                }
            }
            result.onFailure { error ->
                Toast.makeText(
                    this@DiagnosticsActivity,
                    "Could not restart for startup capture: ${error.message ?: "unknown error"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private companion object {
        const val RESTART_REQUEST_CODE = 0xB17
        const val RESTART_DELAY_MILLIS = 750L
    }
}

@Composable
private fun DiagnosticsPanel(
    onShare: () -> Unit,
    onRestartAndCapture: () -> Unit,
) {
    var status by remember { mutableStateOf(Diagnostics.captureStatus()) }
    var mode by remember { mutableStateOf(Diagnostics.diagnosticMode()) }
    var refreshToken by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        status = Diagnostics.captureStatus()
        mode = Diagnostics.diagnosticMode()
        refreshToken += 1
    }

    fun runControl(successMessage: String, block: () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { block() }
            }
            result.onSuccess {
                message = successMessage
                refresh()
            }.onFailure { error ->
                message = error.message ?: "Diagnostics operation failed"
                refresh()
            }
            busy = false
        }
    }

    val recentEvents = remember(refreshToken) {
        Diagnostics.recentEvents().takeLast(40).asReversed()
    }
    val triage = remember(refreshToken) {
        DiagnosticIncidentTriage.build(Diagnostics.recentEvents())
    }
    var incidentCategory by remember { mutableStateOf<DiagnosticCategory?>(null) }
    var incidentOutcome by remember { mutableStateOf<DiagnosticFunctionalTerminal?>(null) }
    var incidentFlow by remember { mutableStateOf<String?>(null) }
    var lastTenMinutes by remember { mutableStateOf(false) }
    val flowOptions = triage.incidents.mapNotNull { it.flowId }.distinct()
    val filteredIncidents = triage.filter(
        DiagnosticIncidentFilter(
            category = incidentCategory,
            outcome = incidentOutcome,
            flowId = incidentFlow,
            sinceUtcMillis = if (lastTenMinutes) System.currentTimeMillis() - 600_000L else null,
        ),
    )
    val remainingMinutes = if (status.active) {
        ceil(status.remainingMillis / 60_000.0).toInt().coerceAtLeast(1)
    } else {
        0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("BitChord Diagnostics", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (status.active) {
                "Capture active · about $remainingMinutes min remaining"
            } else {
                "Capture stopped"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Start capture here, reproduce the problem in BitChord, return here, inspect the events, then share the report. Persistent capture expires automatically after one hour.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedButton(
            onClick = {
                val target = if (mode == DiagnosticMode.STANDARD) {
                    DiagnosticMode.VERBOSE
                } else {
                    DiagnosticMode.STANDARD
                }
                runControl("Diagnostics mode changed to ${target.name.lowercase()}") {
                    check(Diagnostics.setDiagnosticMode(target)) {
                        "Could not persist diagnostics mode"
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (mode == DiagnosticMode.STANDARD) {
                    "Mode: Standard"
                } else {
                    "Mode: Verbose"
                },
            )
        }
        Text(
            if (mode == DiagnosticMode.STANDARD) {
                "Standard records structured traces plus legacy warnings/errors. Legacy Debug/Info free-text is suppressed."
            } else {
                "Verbose also records legacy Debug/Info free-text. Use it only for targeted investigations; this choice persists through restart until changed."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (status.active) {
                Button(
                    onClick = {
                        runControl("Capture stopped") { Diagnostics.stopCapture() }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Stop capture") }
            } else {
                Button(
                    onClick = {
                        runControl("Capture started") {
                            check(Diagnostics.startCapture().active) {
                                "Could not persist capture state"
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Start 1h capture") }
            }
            OutlinedButton(
                onClick = {
                    runControl("Diagnostics cleared; capture state was preserved") {
                        Diagnostics.clear()
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Clear") }
        }

        OutlinedButton(
            onClick = onRestartAndCapture,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Restart & capture startup")
        }
        Text(
            "Persists capture before restarting the app so the next process records a real cold Application startup without ADB.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(
            onClick = onShare,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Share report")
        }
        Text(
            "The report shared from this screen is the canonical diagnostics artifact.",
            style = MaterialTheme.typography.bodySmall,
        )

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (Diagnostics.hasLastCrash()) "Retained crash: yes" else "Retained crash: none",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(
            onClick = { refresh() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Refresh events")
        }

        HorizontalDivider()
        Text("Incident triage (observed evidence)", style = MaterialTheme.typography.titleMedium)
        Text(
            "In-memory window only. A missing stage or event is UNKNOWN; warning clusters do not prove a root cause.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = {
                runCatching {
                    Diagnostics.event(
                        category = DiagnosticCategory.UI,
                        name = "manual_reproduction_marker",
                        metadata = mapOf("trigger" to "user_marked"),
                    )
                }
                refresh()
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Mark: reproduced the problem now") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val options = listOf<DiagnosticCategory?>(null,
                        DiagnosticCategory.UI, DiagnosticCategory.PLAYER,
                        DiagnosticCategory.CATALOG, DiagnosticCategory.NETWORK,
                        DiagnosticCategory.APP, DiagnosticCategory.DATABASE)
                    incidentCategory = options[(options.indexOf(incidentCategory) + 1) % options.size]
                },
                modifier = Modifier.weight(1f),
            ) { Text("Feature: " + (incidentCategory?.name ?: "ALL")) }
            OutlinedButton(
                onClick = {
                    val options = listOf<DiagnosticFunctionalTerminal?>(null,
                        DiagnosticFunctionalTerminal.UNKNOWN, DiagnosticFunctionalTerminal.FAILED,
                        DiagnosticFunctionalTerminal.RECOVERED, DiagnosticFunctionalTerminal.CANCELLED)
                    incidentOutcome = options[(options.indexOf(incidentOutcome) + 1) % options.size]
                },
                modifier = Modifier.weight(1f),
            ) { Text("Outcome: " + (incidentOutcome?.name ?: "ALL")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { lastTenMinutes = !lastTenMinutes },
                modifier = Modifier.weight(1f),
            ) { Text(if (lastTenMinutes) "Window: 10m" else "Window: ALL") }
            OutlinedButton(
                onClick = {
                    val options = listOf<String?>(null) + flowOptions
                    incidentFlow = options[(options.indexOf(incidentFlow).coerceAtLeast(0) + 1) % options.size]
                },
                modifier = Modifier.weight(1f),
            ) { Text("Flow: " + (incidentFlow ?: "ALL")) }
        }
        if (filteredIncidents.isEmpty()) {
            Text("No incidents match current filters.")
        } else {
            filteredIncidents.take(40).forEach { item ->
                Text(
                    item.incidentId + " · " + item.category.name + " · " + item.reasonCode +
                        " · count=" + item.occurrences + " · " + item.outcome.name +
                        " · cause=UNKNOWN · last confirmed=" + (item.lastConfirmedStage ?: "UNKNOWN"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        HorizontalDivider()
        Text("Recent events", style = MaterialTheme.typography.titleMedium)
        if (recentEvents.isEmpty()) {
            Text("No diagnostic events in this process yet.")
        } else {
            recentEvents.forEach { event ->
                Text(
                    DiagnosticFormatter.format(event),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
