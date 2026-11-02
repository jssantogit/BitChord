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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.ui.theme.BitChordTheme
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.screens.SettingsGroupSurface
import com.music.bitchord.ui.screens.SettingsRow
import com.music.bitchord.ui.screens.PlaylistWizardPrimaryAction
import com.music.bitchord.ui.screens.PlaylistWizardSecondaryAction
import com.music.bitchord.ui.components.ArtworkPageBackButton
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
import androidx.compose.ui.Alignment
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
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            BitChordTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DiagnosticsPanel(
                        onBack = ::finish,
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
    onBack: () -> Unit,
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
    var minimumSeverity by remember { mutableStateOf<DiagnosticLevel?>(null) }
    var incidentFlow by remember { mutableStateOf<String?>(null) }
    var lastTenMinutes by remember { mutableStateOf(false) }
    val flowOptions = triage.incidents.mapNotNull { it.flowId }.distinct()
    val filteredIncidents = triage.filter(
        DiagnosticIncidentFilter(
            category = incidentCategory,
            outcome = incidentOutcome,
            minimumLevel = minimumSeverity,
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
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtworkPageBackButton(onClick = onBack, hazeState = null)
            Spacer(Modifier.width(16.dp))
            Text("Logs", style = MaterialTheme.typography.headlineLarge)
        }
        SettingsGroupSurface {
            SettingsRow(
                title = if (status.active) "Captura em andamento" else "Captura parada",
                subtitle = if (status.active) "Tempo restante: cerca de $remainingMinutes min"
                    else "Nenhuma captura em andamento",
                reserveIconSpace = false,
            )
        }
        Text(
            "Inicie a captura, reproduza o problema e volte aqui para compartilhar o relatório. A coleta é encerrada automaticamente após uma hora.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PlaylistWizardSecondaryAction(
            label = if (mode == DiagnosticMode.STANDARD) "Modo: Padrão"
                else "Modo: Detalhado",
            enabled = !busy,
            onClick = {
                val target = if (mode == DiagnosticMode.STANDARD) {
                    DiagnosticMode.VERBOSE
                } else DiagnosticMode.STANDARD
                runControl("Modo de diagnóstico alterado") {
                    check(Diagnostics.setDiagnosticMode(target)) {
                        "Não foi possível salvar o modo"
                    }
                }
            },
        )
        Text(
            if (mode == DiagnosticMode.STANDARD) {
                "Standard records structured traces plus legacy warnings/errors. Legacy Debug/Info free-text is suppressed."
            } else {
                "Verbose also records legacy Debug/Info free-text. Use it only for targeted investigations; this choice persists through restart until changed."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlaylistWizardPrimaryAction(
                label = if (status.active) "Parar captura" else "Iniciar captura · 1h",
                enabled = !busy,
                onClick = {
                    if (status.active) runControl("Captura parada") {
                        Diagnostics.stopCapture()
                    } else runControl("Captura iniciada") {
                        check(Diagnostics.startCapture().active) {
                            "Não foi possível iniciar a captura"
                        }
                    }
                },
            )
            PlaylistWizardSecondaryAction(
                label = "Limpar eventos",
                enabled = !busy,
                onClick = {
                    runControl("Eventos limpos; estado da captura preservado") {
                        Diagnostics.clear()
                    }
                },
            )
        }

        OutlinedButton(
            onClick = onRestartAndCapture,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reiniciar e capturar inicialização")
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
            Text("Compartilhar relatório")
        }
        SettingsGroupSurface {
            SettingsRow(
                title = "Privacidade do relatório",
                subtitle = "O diagnóstico pode conter informações sensíveis da sessão. Confira o arquivo antes de compartilhar.",
                reserveIconSpace = false,
            )
        }

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
            Text("Atualizar eventos")
        }

        HorizontalDivider()
        Text("Triagem de incidentes", style = MaterialTheme.typography.headlineMedium)
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
        OutlinedButton(
            onClick = {
                val options = listOf<DiagnosticLevel?>(null, DiagnosticLevel.WARN, DiagnosticLevel.ERROR)
                minimumSeverity = options[(options.indexOf(minimumSeverity) + 1) % options.size]
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Minimum severity: " + (minimumSeverity?.name ?: "ALL")) }
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
        if (incidentFlow != null) {
            Text("Selected flow timeline (observed points only)", style = MaterialTheme.typography.titleMedium)
            val flowTimeline = remember(refreshToken, incidentFlow) {
                Diagnostics.recentEvents().filter { it.metadata["flowId"] == incidentFlow }
                    .sortedWith(compareBy<DiagnosticEvent> { it.epochMillis }.thenBy { it.sequence })
                    .takeLast(30)
            }
            if (flowTimeline.isEmpty()) {
                Text("No flow evidence in this in-memory window. Missing points are UNKNOWN.")
            } else {
                flowTimeline.forEach { item ->
                    Text(
                        item.sequence.toString() + " · " + item.name +
                            " · " + (item.metadata["stage"] ?: "unknown") +
                            " · " + (item.metadata["outcome"] ?: "unknown"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        HorizontalDivider()
        Text("Eventos recentes", style = MaterialTheme.typography.headlineMedium)
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
