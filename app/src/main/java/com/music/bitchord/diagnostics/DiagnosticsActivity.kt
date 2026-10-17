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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Developer-facing control panel. Registered only by the `dev` flavor manifest,
 * so production users never get an exported diagnostics surface.
 */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiagnosticsPanel(onShare = ::shareDiagnostics)
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
private fun DiagnosticsPanel(onShare: () -> Unit) {
    var status by remember { mutableStateOf(Diagnostics.captureStatus()) }
    var refreshToken by remember { mutableStateOf(0) }

    fun refresh() {
        status = Diagnostics.captureStatus()
        refreshToken += 1
    }

    val recentEvents = remember(refreshToken) {
        Diagnostics.recentEvents().takeLast(40).asReversed()
    }
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
            "Structured events stay in a bounded memory buffer. Persistent capture is opt-in and expires automatically after one hour.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (status.active) {
                Button(
                    onClick = {
                        Diagnostics.stopCapture()
                        refresh()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Stop capture") }
            } else {
                Button(
                    onClick = {
                        Diagnostics.startCapture()
                        refresh()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Start 1h capture") }
            }
            OutlinedButton(
                onClick = {
                    Diagnostics.clear()
                    refresh()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Clear") }
        }

        OutlinedButton(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Share report")
        }

        Text(
            if (Diagnostics.hasLastCrash()) "Retained crash: yes" else "Retained crash: none",
            style = MaterialTheme.typography.bodySmall,
        )

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
