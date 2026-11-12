package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.ArtworkPageBackButton
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.components.appleSettingsSwitchColors
import com.music.bitchord.ui.theme.appleMusicKeyColor

/**
 * Spotify Canvas account settings reuse the native Settings language instead
 * of an independent Material3 Scaffold/TopAppBar/OutlinedTextField composition.
 * The SPDC token is masked in the UI; storage and save callbacks are unchanged.
 */
@Composable
fun SpotifyCanvasAuthScreen(onNavigateUp: () -> Unit) {
    val currentToken by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    val autoHidePlayer by AppSettings.spotifyCanvasAutoHide.collectAsStateWithLifecycle()
    val prioritizeSpotify by AppSettings.prioritizeSpotifyCanvas.collectAsStateWithLifecycle()
    var tokenInput by remember(currentToken) { mutableStateOf(currentToken) }

    Column(
        modifier = Modifier.fillMaxSize()
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtworkPageBackButton(onClick = onNavigateUp, hazeState = null)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.spotify_canvas_setup),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            stringResource(R.string.spotify_canvas_setup_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        SpotifyCanvasSettingToggle(
            title = stringResource(R.string.spotify_canvas_auto_hide),
            subtitle = stringResource(R.string.spotify_canvas_auto_hide_subtitle),
            checked = autoHidePlayer,
            onCheckedChange = AppSettings::setSpotifyCanvasAutoHide,
        )
        SpotifyCanvasSettingToggle(
            title = stringResource(R.string.prioritize_spotify_canvas),
            subtitle = stringResource(R.string.prioritize_spotify_canvas_subtitle),
            checked = prioritizeSpotify,
            onCheckedChange = AppSettings::setPrioritizeSpotifyCanvas,
        )
        Text(
            stringResource(R.string.spotify_canvas_setup_steps),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val tokenLabel = stringResource(R.string.spdc_token)
            Text(
                tokenLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PillTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                placeholder = tokenLabel,
                isPassword = true,
                accessibilityLabel = tokenLabel,
            )
        }
        Button(
            onClick = {
                AppSettings.setSpotifySpdcToken(tokenInput.trim())
                onNavigateUp()
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = appleMusicKeyColor(),
                contentColor = Color.White,
            ),
        ) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun SpotifyCanvasSettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = appleSettingsSwitchColors(),
        )
    }
}
