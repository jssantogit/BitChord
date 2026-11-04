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
        if (intent.getBooleanExtra("share_only", false)) {
            // The page itself stays inside MainActivity. Only Android's share
            // chooser requires this dev-only FileProvider host.
            shareDiagnostics()
            return
        }
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
                if (intent.getBooleanExtra("share_only", false)) finish()
            }.onFailure { error ->
                Toast.makeText(
                    this@DiagnosticsActivity,
                    "Could not create diagnostics report: ${error.message ?: "unknown error"}",
                    Toast.LENGTH_LONG,
                ).show()
                if (intent.getBooleanExtra("share_only", false)) finish()
            }
        }
    }


}

