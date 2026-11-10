package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.ArtworkPageBackButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.music.bitchord.ui.icons.PlaylistEditorIcons
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
) {
    // First-party native back, typography and segmented selector. Filters
    // are the entry point, not a legacy playlist-management dashboard.
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = com.music.bitchord.ui.components.PAGE_GUTTER),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkPageBackButton(onClick = onBack, hazeState = null)
            Text(
                "Gerenciar coleções",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
        SegmentedControl(
            options = listOf("Filtros", "Inteligentes", "Clássicas"),
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
    // Native neutral outlined action: unmistakable touch target without
    // competing with the single red primary CTA or tinting text red.
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("playlist-wizard-secondary-button"),
        shape = GroupShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Compact variant of the same neutral outlined action for sheets and row tools. */
@Composable
internal fun PlaylistWizardCompactAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    // Same native outlined control; optional native icon instead of visible text.
    // Keep the label as the accessibility description and minimum touch target.
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = if (icon == null) modifier.heightIn(min = 42.dp)
            else modifier.size(40.dp).testTag("playlist-editor-icon-action"),
        shape = if (icon == null) GroupShape else CircleShape,
        contentPadding = if (icon == null) ButtonDefaults.ContentPadding
            else PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (icon != null) Icon(icon, contentDescription = label,
            modifier = Modifier.size(20.dp))
        else Text(label, style = MaterialTheme.typography.bodyMedium)
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
        shape = RoundedCornerShape(25.dp),
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
    emphasized: Boolean = false,
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
        // The shared field already has a native boundary; avoid a second
        // competing outline or a dark-on-dark inset panel.
        PillTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = if (multiline) "Adicionar descrição" else "Digite aqui",
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
        // Settings-style contiguous rows, not a stack of oversized cards.
        // Smaller native badges retain clear category affordances and a
        // minimum 64dp row target without wasting a screen per seven options.
        SettingsGroupSurface(modifier = Modifier.testTag("playlist-category-group")) {
            options.forEachIndexed { index, option ->
                SettingsRow(
                    title = option.title,
                    subtitle = option.description.ifBlank { null },
                    onClick = option.onClick,
                    reserveIconSpace = false,
                    minHeight = 64.dp,
                    trailing = {},
                    leading = {
                        Box(
                            modifier = Modifier.size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                .testTag("playlist-category-icon-badge"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = option.icon ?: PlaylistEditorIcons.Music,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
                if (index < options.lastIndex) RowDivider()
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
                    trailing = {},
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
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    // One native SettingsRow used by the wizard AND by the unified Library's
    // playlist destination picker. Keep its artwork slot, insets and selected
    // semantics in one place; do not invent another row composition.
    SettingsRow(
        title = label,
        subtitle = subtitle,
        leading = leading,
        onClick = onClick,
        reserveIconSpace = false,
        modifier = Modifier.testTag("native-playlist-selection-row")
            .semantics { this.selected = selected },
        trailing = {
            if (selected) {
                Icon(
                    PlaylistEditorIcons.Check,
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
        icon = PlaylistEditorIcons.Music,
        modifier = Modifier.size(dimension).clip(SLEEVE_SHAPE)
            .semantics { contentDescription = "Capa padrão da playlist" },
        iconFraction = 0.39f,
    )
}
