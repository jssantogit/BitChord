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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.music.bitchord.ui.icons.BitChordIcons
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
        // Match the library's drill-down rhythm: a native floating back
        // above the section title, rather than an administration toolbar.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkPageBackButton(onClick = onBack, hazeState = null)
        }
        Text(
            "Gerenciar coleções",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        SegmentedControl(
            options = listOf("Filtros", "Inteligentes", "Clássicas"),
            selectedIndex = selectedIndex,
            onSelect = onSelect,
        )
     }
}

/**
 * Library-style empty state for collections. A non-clickable SettingsRow looked
 * like a broken preference and left the page without a visual focal point.
 * Keeping this outside a card also avoids suggesting that the empty state
 * itself can be tapped.
 */
@Composable
internal fun PlaylistManagerEmptyState(
    title: String,
    message: String,
    icon: ImageVector,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(top = 72.dp, bottom = 48.dp, start = 20.dp, end = 20.dp)
            .testTag("playlist-manager-empty-state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(60.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
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

/** Secondary actions belong to the footer, but must not compete with Save. */
@Composable
internal fun PlaylistWizardTextAction(
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .testTag("playlist-wizard-text-action"),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
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

/** Actions in rule groups are navigation commands, not large form buttons. */
internal data class PlaylistWizardRuleAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
internal fun PlaylistWizardRuleActions(options: List<PlaylistWizardRuleAction>) {
    if (options.isEmpty()) return
    SettingsGroupSurface {
        options.forEachIndexed { index, option ->
            SettingsRow(
                title = option.label,
                icon = option.icon,
                iconTint = MaterialTheme.colorScheme.onSurface,
                enabled = option.enabled,
                onClick = option.onClick,
                reserveIconSpace = false,
                minHeight = 48.dp,
            )
            if (index < options.lastIndex) RowDivider()
        }
    }
}

internal data class PlaylistWizardNavOption(
    val title: String,
    val description: String = "",
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onClick: () -> Unit,
)

@Composable
private fun PlaylistWizardNavigationSubtitle(description: String) {
    Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Field-picker composition follows the search result list: no grouped card,
 * category section headers, filter chips or trailing navigation chevrons.
 * The field itself is the entire touch target. Colours and glyphs remain
 * BitChord's, not the reference mock's blue assets.
 */
@Composable
internal fun PlaylistWizardFieldList(options: List<PlaylistWizardNavOption>) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("playlist-field-flat-list"),
    ) {
        options.forEachIndexed { index, option ->
            androidx.compose.material3.Surface(
                onClick = option.onClick,
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth().testTag("playlist-field-row"),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = option.icon ?: PlaylistEditorIcons.Music,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            option.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (option.description.isNotBlank()) {
                            PlaylistWizardNavigationSubtitle(option.description)
                        }
                    }
                }
            }
            if (index < options.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 48.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/** Native library/settings rows, not independent Material3 action cards. */
@Composable
internal fun PlaylistWizardNavigationList(options: List<PlaylistWizardNavOption>) {
    val categories = options.isNotEmpty() && options.all { it.icon != null }
    SettingsGroupSurface(
        modifier = if (categories) Modifier.testTag("playlist-category-group")
            else Modifier,
    ) {
        options.forEachIndexed { index, option ->
            SettingsRow(
                title = option.title,
                subtitleContent = if (option.description.isNotBlank()) {
                    { PlaylistWizardNavigationSubtitle(option.description) }
                } else null,
                onClick = option.onClick,
                reserveIconSpace = false,
                minHeight = if (categories) 64.dp else 52.dp,
                leading = if (categories) {
                    {
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
                    }
                } else null,
                trailing = {
                    Icon(
                        imageVector = BitChordIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                            .testTag("playlist-navigation-chevron"),
                    )
                },
            )
            if (index < options.lastIndex) RowDivider()
        }
    }
}


/**
 * Compact two-column selection surface for the seven top-level rule families.
 * The final unpaired category spans the available width rather than creating
 * a visually empty right-hand slot. Nested field navigation stays a native list.
 * These proportions are a BitChord adaptation, not measured Apple Music tokens.
 */
@Composable
internal fun PlaylistWizardCategoryGrid(options: List<PlaylistWizardNavOption>) {
    if (options.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().testTag("playlist-category-grid"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.chunked(2).forEach { pair ->
            if (pair.size == 1) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .testTag("playlist-category-wide-tile"),
                ) {
                    PlaylistWizardCategoryTile(
                        option = pair.first(),
                        modifier = Modifier.fillMaxWidth(),
                        wide = true,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PlaylistWizardCategoryTile(pair[0], Modifier.weight(1f), wide = false)
                    PlaylistWizardCategoryTile(pair[1], Modifier.weight(1f), wide = false)
                }
            }
        }
    }
}

@Composable
private fun PlaylistWizardCategoryTile(
    option: PlaylistWizardNavOption,
    modifier: Modifier,
    wide: Boolean,
) {
    androidx.compose.material3.Surface(
        onClick = option.onClick,
        modifier = modifier.heightIn(min = if (wide) 76.dp else 116.dp)
            .testTag("playlist-category-tile"),
        shape = GroupShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
    ) {
        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlaylistWizardCategoryGlyph(option.icon)
                Box(modifier = Modifier.weight(1f)) {
                    PlaylistWizardCategoryLabels(option)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                PlaylistWizardCategoryGlyph(option.icon)
                PlaylistWizardCategoryLabels(option)
            }
        }
    }
}

@Composable
private fun PlaylistWizardCategoryGlyph(icon: ImageVector?) {
    Box(
        modifier = Modifier.size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .testTag("playlist-category-icon"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon ?: PlaylistEditorIcons.Music,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PlaylistWizardCategoryLabels(option: PlaylistWizardNavOption) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            option.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (option.description.isNotBlank()) {
            Text(
                option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
