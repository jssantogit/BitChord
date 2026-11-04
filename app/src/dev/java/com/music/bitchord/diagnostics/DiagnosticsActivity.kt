package com.music.bitchord.diagnostics

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
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


}

@Composable
private fun DiagnosticsPanel(
    onBack: () -> Unit,
    onShare: () -> Unit,
) {
    var status by remember { mutableStateOf(Diagnostics.captureStatus()) }
    var mode by remember { mutableStateOf(Diagnostics.diagnosticMode()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        status = Diagnostics.captureStatus()
        mode = Diagnostics.diagnosticMode()
    }

    fun runControl(successMessage: String, block: () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { block() }
            }
            result.onSuccess { message = successMessage }
                .onFailure { error ->
                    message = error.message ?: "Não foi possível executar a operação."
                }
            refresh()
            busy = false
        }
    }

    val remainingMinutes = if (status.active) {
        ceil(status.remainingMillis / 60_000.0).toInt().coerceAtLeast(1)
    } else 0

    Column(
        modifier = Modifier.fillMaxSize()
            .statusBarsPadding().navigationBarsPadding()
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
            "Inicie a captura, reproduza o problema e compartilhe o relatório. A coleta termina automaticamente após uma hora.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A native Settings row replaces the Material3 controls and technical
        // text. Verbose mode remains available without cluttering the screen.
        SettingsGroupSurface {
            SettingsRow(
                title = if (mode == DiagnosticMode.STANDARD)
                    "Modo de diagnóstico · Padrão" else "Modo de diagnóstico · Detalhado",
                subtitle = if (mode == DiagnosticMode.STANDARD)
                    "Eventos estruturados e avisos importantes"
                else "Inclui eventos detalhados para investigação",
                reserveIconSpace = false,
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
        }
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
        PlaylistWizardSecondaryAction(
            label = "Compartilhar relatório",
            enabled = !busy,
            onClick = onShare,
        )
        message?.let { result ->
            Text(
                result,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Incident triage, retained crashes, restart capture, and detailed
        // traces are intentionally absent from this UI, not from diagnostics.
        // Diagnostics.createExport() still serializes the full evidence.
    }
}
