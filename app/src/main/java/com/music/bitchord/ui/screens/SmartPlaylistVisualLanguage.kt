package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.ArtworkPageBackButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.music.bitchord.ui.theme.AccentRed
import com.music.bitchord.ui.theme.themeArtworkPalette

/**
 * The wizard borrows established Settings/Detail components. It is a workflow
 * composed from native controls, not a second Material3 design language.
 */
/**
 * Navigation for saved filters/playlists is composed entirely from existing
 * BitChord chrome: native floating back, text actions and Settings segments.
 */
@Composable
internal fun PlaylistManagerChrome(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onDraft: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = com.music.bitchord.ui.components.PAGE_GUTTER),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkPageBackButton(onClick = onBack, hazeState = null)
            Text(
                "Filtros e versões anteriores",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onNew) { Text("Nova playlist") }
            TextButton(onClick = onDraft) { Text("Retomar rascunho") }
        }
        SegmentedControl(
            options = listOf("Inteligentes", "Filtros", "Clássicas"),
            selectedIndex = selectedIndex,
            onSelect = onSelect,
        )
    }
}

@Composable
internal fun PlaylistWizardSecondaryAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // The native Settings/Detail screens use text actions for secondary work.
    // Avoid adding a second tall, filled Material3 button in a card.
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun PlaylistWizardPrimaryAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = GroupShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentRed,
            contentColor = androidx.compose.ui.graphics.Color.White,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun PlaylistWizardTextEntry(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    multiline: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Reuse the same field as Settings and dialogs. Only the label and
        // validation text are specific to this wizard.
        PillTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "Toque para preencher",
            container = MaterialTheme.colorScheme.surfaceVariant,
            keyboardOptions = keyboardOptions,
            accessibilityLabel = label,
            multiline = multiline,
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
internal fun PlaylistWizardSearchEntry(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    // Exact field already used by the native DetailScreen and playlist library:
    // search icon, glass treatment, 46dp target, text cursor and clear action.
    DetailSearchField(
        query = value,
        onQueryChange = onValueChange,
        onClose = { onValueChange("") },
        autoFocus = false,
        onFocused = {},
        palette = themeArtworkPalette(),
        placeholder = placeholder,
        horizontalInset = 0.dp,
    )
}

@Composable
internal fun PlaylistWizardSegmented(
    first: String,
    second: String,
    firstSelected: Boolean,
    onSelectFirst: () -> Unit,
    onSelectSecond: () -> Unit,
) {
    // The local music browser already owns the segmented UI and its behavior.
    SegmentedControl(
        options = listOf(first, second),
        selectedIndex = if (firstSelected) 0 else 1,
        onSelect = { if (it == 0) onSelectFirst() else onSelectSecond() },
    )
}

internal data class PlaylistWizardNavOption(
    val title: String,
    val description: String = "",
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onClick: () -> Unit,
)

@Composable
internal fun PlaylistWizardNavigationList(options: List<PlaylistWizardNavOption>) {
    val categories = options.isNotEmpty() && options.all { it.icon != null }
    if (categories) {
        // The native Settings surface stays the source of truth, but each
        // category gets its own row-sized card. This avoids one dense block
        // followed by a large empty lower half on tall phones.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { option ->
                SettingsGroupSurface {
                    SettingsRow(
                        icon = option.icon,
                        title = option.title,
                        subtitle = option.description.ifBlank { null },
                        onClick = option.onClick,
                        reserveIconSpace = false,
                        iconTint = AccentRed,
                        minHeight = 88.dp,
                    )
                }
            }
        }
    } else {
        // Drill-down rows remain the same contiguous settings list used
        // throughout the application, including their native dividers.
        SettingsGroupSurface {
            options.forEachIndexed { index, option ->
                SettingsRow(
                    icon = option.icon,
                    title = option.title,
                    subtitle = option.description.ifBlank { null },
                    onClick = option.onClick,
                    reserveIconSpace = false,
                    iconTint = AccentRed,
                )
                if (index < options.lastIndex) RowDivider()
            }
        }
    }
}

@Composable
internal fun PlaylistWizardSelectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // A normal SettingsRow in its parent Settings group; no nested custom
    // grey rounded rectangles for every single option.
    SettingsRow(
        title = label,
        onClick = onClick,
        reserveIconSpace = false,
        trailing = {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Selecionado",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

/**
 * A deliberately neutral local cover placeholder. The V2 definition has no
 * persisted artwork field, so this is NOT a claimed album/track image or an
 * inert "change cover" button. Artwork editing can become interactive only
 * after the playlist-art persistence contract exists.
 */
@Composable
internal fun PlaylistWizardArtworkPlaceholder(
    dimension: Dp = 224.dp,
) {
    // The normal release/folder placeholder already defines this treatment.
    CoverPlaceholder(
        icon = Icons.Rounded.LibraryMusic,
        modifier = Modifier.size(dimension).clip(SLEEVE_SHAPE)
            .semantics { contentDescription = "Capa padrão da playlist" },
        iconFraction = 0.39f,
    )
}
