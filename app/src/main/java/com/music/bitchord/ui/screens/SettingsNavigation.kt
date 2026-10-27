package com.music.bitchord.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R

/**
 * Navigation destinations for the existing Android settings groups.
 * This is presentation-only: preferences continue to be owned by AppSettings.
 */
internal enum class SettingsSection(@StringRes val titleRes: Int, val icon: ImageVector) {
    ACCOUNT(R.string.account_integrations, Icons.Rounded.Person),
    AUDIO_QUALITY(R.string.audio_quality, Icons.Rounded.GraphicEq),
    PLAYBACK(R.string.playback, Icons.Rounded.PlaylistPlay),
    LOCAL_MUSIC(R.string.local_music, Icons.Rounded.LibraryMusic),
    APPEARANCE(R.string.appearance, Icons.Rounded.Brightness4),
    LYRICS(R.string.settings_nav_lyrics, Icons.AutoMirrored.Rounded.Notes),
    DOWNLOADS(R.string.downloads, Icons.Rounded.Download),
    STORAGE(R.string.storage, Icons.Rounded.Storage),
    YOUR_DATA(R.string.your_data, Icons.Rounded.Folder),
    PERFORMANCE(R.string.performance, Icons.Rounded.BarChart),
    MISCELLANEOUS(R.string.miscellaneous, Icons.Rounded.Tune),
    LANGUAGE(R.string.language, Icons.Rounded.Language),
    ADVANCED(R.string.advanced_options, Icons.Rounded.Extension),
    WEAR(R.string.wear_os_page_title, Icons.Rounded.Apps),
    ABOUT(R.string.settings_nav_about, Icons.Rounded.Info),
}

internal data class SettingsSectionGroup(
    @StringRes val titleRes: Int,
    val sections: List<SettingsSection>,
)

/** Every accessible group occurs once. No parallel registry of preference state. */
internal object SettingsNavigation {
    val groups = listOf(
        SettingsSectionGroup(R.string.settings_nav_group_account, listOf(SettingsSection.ACCOUNT)),
        SettingsSectionGroup(
            R.string.settings_nav_group_music,
            listOf(
                SettingsSection.AUDIO_QUALITY,
                SettingsSection.PLAYBACK,
                SettingsSection.LOCAL_MUSIC,
            ),
        ),
        SettingsSectionGroup(
            R.string.settings_nav_group_experience,
            listOf(SettingsSection.APPEARANCE, SettingsSection.LYRICS),
        ),
        SettingsSectionGroup(
            R.string.settings_nav_group_data,
            listOf(SettingsSection.DOWNLOADS, SettingsSection.STORAGE, SettingsSection.YOUR_DATA),
        ),
        SettingsSectionGroup(
            R.string.settings_nav_group_system,
            listOf(
                SettingsSection.PERFORMANCE,
                SettingsSection.MISCELLANEOUS,
                SettingsSection.LANGUAGE,
                SettingsSection.ADVANCED,
                SettingsSection.WEAR,
            ),
        ),
        SettingsSectionGroup(R.string.settings_nav_group_about, listOf(SettingsSection.ABOUT)),
    )
}

@Composable
internal fun SettingsHome(
    onSelect: (SettingsSection) -> Unit,
    showWear: Boolean = true,
) {
    SettingsNavigation.groups.forEach { group ->
        val visible = group.sections.filter { it != SettingsSection.WEAR || showWear }
        if (visible.isNotEmpty()) {
            SettingsGroup(header = stringResource(group.titleRes)) {
                visible.forEachIndexed { index, section ->
                    if (index > 0) RowDivider()
                    SettingsRow(
                        icon = section.icon,
                        title = stringResource(section.titleRes),
                        onClick = { onSelect(section) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsSearchDestination(
    section: SettingsSection,
    title: String,
    onNavigate: (SettingsSection, String) -> Unit,
) {
    SettingsRow(
        icon = section.icon,
        title = title,
        subtitle = stringResource(section.titleRes),
        onClick = { onNavigate(section, title) },
    )
}

@Composable
internal fun SettingsDetailHeader(
    section: SettingsSection,
    filteredQuery: String,
    onBack: () -> Unit,
    onClearFilter: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(section.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
    if (filteredQuery.isNotBlank()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_nav_filtered, filteredQuery),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearFilter) {
                Text(stringResource(R.string.settings_nav_show_all))
            }
        }
    }
}
