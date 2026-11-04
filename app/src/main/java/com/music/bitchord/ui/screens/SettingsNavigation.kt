package com.music.bitchord.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.music.bitchord.R

/**
 * Navigation destinations for the existing Android settings groups.
 * This is presentation-only: preferences continue to be owned by AppSettings.
 */
internal enum class SettingsSection(@StringRes val titleRes: Int, val icon: ImageVector, @StringRes val subtitleRes: Int) {
    ACCOUNT(R.string.account_integrations, Icons.Rounded.Person, R.string.settings_nav_desc_account),
    SOURCES(R.string.sources, Icons.Rounded.Extension, R.string.settings_nav_desc_sources),
    AUDIO_QUALITY(R.string.audio_quality, Icons.Rounded.GraphicEq, R.string.settings_nav_desc_audio),
    PLAYBACK(R.string.playback, Icons.Rounded.PlaylistPlay, R.string.settings_nav_desc_playback),
    LOCAL_MUSIC(R.string.local_music, Icons.Rounded.LibraryMusic, R.string.settings_nav_desc_local),
    APPEARANCE(R.string.appearance, Icons.Rounded.Brightness4, R.string.settings_nav_desc_appearance),
    LYRICS(R.string.settings_nav_lyrics, Icons.AutoMirrored.Rounded.Notes, R.string.settings_nav_desc_lyrics),
    DOWNLOADS(R.string.downloads, Icons.Rounded.Download, R.string.settings_nav_desc_downloads),
    STORAGE(R.string.storage, Icons.Rounded.Storage, R.string.settings_nav_desc_storage),
    YOUR_DATA(R.string.your_data, Icons.Rounded.Folder, R.string.settings_nav_desc_data),
    PERFORMANCE(R.string.performance, Icons.Rounded.BarChart, R.string.settings_nav_desc_performance),
    MISCELLANEOUS(R.string.settings_nav_controls, Icons.Rounded.Tune, R.string.settings_nav_desc_misc),
    LANGUAGE(R.string.language, Icons.Rounded.Language, R.string.settings_nav_desc_language),
    ADVANCED(R.string.settings_nav_advanced, Icons.Rounded.Extension, R.string.settings_nav_desc_advanced),
    WEAR(R.string.wear_os_page_title, Icons.Rounded.Apps, R.string.settings_nav_desc_wear),
    ABOUT(R.string.settings_nav_about, Icons.Rounded.Info, R.string.settings_nav_desc_about),
}

/**
 * Category pages already have a prominent page title. A second group caption
 * would redundantly repeat the same category; global search still needs labels
 * to distinguish hits from different destinations.
 */
internal fun settingsGroupCaption(isSearchResult: Boolean, groupCaption: String?): String? =
    if (isSearchResult) groupCaption else null

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
                SettingsSection.SOURCES,
                SettingsSection.AUDIO_QUALITY,
                SettingsSection.PLAYBACK,
                SettingsSection.MISCELLANEOUS,
                SettingsSection.LOCAL_MUSIC,
            ),
        ),
        SettingsSectionGroup(
            R.string.settings_nav_group_experience,
            listOf(SettingsSection.APPEARANCE, SettingsSection.LYRICS, SettingsSection.LANGUAGE),
        ),
        SettingsSectionGroup(
            R.string.settings_nav_group_data,
            listOf(SettingsSection.DOWNLOADS, SettingsSection.STORAGE, SettingsSection.YOUR_DATA),
        ),
        SettingsSectionGroup(
            R.string.settings_nav_group_system,
            listOf(
                SettingsSection.PERFORMANCE,
                SettingsSection.ADVANCED,
                SettingsSection.WEAR,
            ),
        ),
        SettingsSectionGroup(R.string.settings_nav_group_about, listOf(SettingsSection.ABOUT)),
    )
}

/** The canonical MusicBrainz catalog is a source; global search may surface it. */
internal fun canonicalDiscoverySettingsVisible(
    section: SettingsSection?,
    searchQuery: String,
): Boolean = section == SettingsSection.SOURCES ||
    (section == null && searchQuery.isNotBlank())

@Composable
internal fun SettingsHome(
    onSelect: (SettingsSection) -> Unit,
    showWear: Boolean = true,
    onListenTogether: (() -> Unit)? = null,
) {
    SettingsNavigation.groups.forEach { group ->
        val visible = group.sections.filter { it != SettingsSection.WEAR || showWear }
        if (visible.isNotEmpty()) {
            SettingsGroup(header = stringResource(group.titleRes)) {
                visible.forEachIndexed { index, section ->
                    if (index > 0) RowDivider()
                    SettingsCategoryRow(
                        icon = section.icon,
                        title = stringResource(section.titleRes),
                        subtitle = stringResource(section.subtitleRes),
                        onClick = { onSelect(section) },
                        testTag = "settings-category-${section.name}",
                    )
                }
                if (group.titleRes == R.string.settings_nav_group_account && onListenTogether != null) {
                    RowDivider()
                    SettingsCategoryRow(
                        icon = Icons.Rounded.Groups,
                        title = stringResource(R.string.listen_together),
                        subtitle = stringResource(R.string.listen_together_subtitle),
                        onClick = onListenTogether,
                        testTag = "settings-category-LISTEN_TOGETHER",
                    )
                }
            }
        }
    }
}

/**
 * Root-only presentation: the small icon tile borrows the app's existing colors.
 * Detail pages retain their original SettingsRow controls and behavior.
 */
@Composable
private fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
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
    onClearFilter: () -> Unit,
) {
    // MainActivity owns the floating top-bar back button for both hierarchy levels.
    // Rendering another arrow here would duplicate that navigation control.
    Text(
        text = stringResource(section.titleRes),
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
    )
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
