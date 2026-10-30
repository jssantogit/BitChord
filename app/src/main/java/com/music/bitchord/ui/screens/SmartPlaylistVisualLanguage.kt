package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.components.CoverPlaceholder
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.music.bitchord.ui.theme.AccentRed

/**
 * Local BitChord/Apple Music treatment for the Android playlist flow.
 *
 * Uses existing BitChordTheme colours and SF Pro Display. AccentRed is the
 * established Apple Music red from Theme.kt, not a new Material 3 colorScheme.
 * These components do not change UI or domain state on their own.
 */
@Composable
internal fun PlaylistWizardSecondaryAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Native Material button keeps focus, ripple and disabled semantics.
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = AccentRed,
        ),
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
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(15.dp),
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
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        PillTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
            accessibilityLabel = placeholder,
        )
    }
}

@Composable
internal fun PlaylistWizardSegmented(
    first: String,
    second: String,
    firstSelected: Boolean,
    onSelectFirst: () -> Unit,
    onSelectSecond: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(first, second).forEachIndexed { index, text ->
            val selected = (index == 0) == firstSelected
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 43.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.outline
                        else androidx.compose.ui.graphics.Color.Transparent,
                    )
                    .clickable(onClick = if (index == 0) onSelectFirst else onSelectSecond),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
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
internal fun PlaylistWizardNavigationList(
    options: List<PlaylistWizardNavOption>,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        options.forEachIndexed { index, option ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(onClick = option.onClick)
                    .heightIn(min = 58.dp)
                    .padding(start = 16.dp, end = 13.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (option.icon != null) {
                    Box(
                        modifier = Modifier.size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(option.icon, contentDescription = null,
                            tint = AccentRed, modifier = Modifier.size(20.dp))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(option.title, style = MaterialTheme.typography.titleMedium)
                    if (option.description.isNotEmpty()) {
                        Text(option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp))
            }
            if (index < options.lastIndex) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(start = if (option.icon != null) 64.dp else 16.dp)
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
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
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge)
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = "Selecionado",
                tint = AccentRed, modifier = Modifier.size(20.dp))
        }
    }
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
        modifier = Modifier.size(dimension).clip(RoundedCornerShape(18.dp))
            .semantics { contentDescription = "Capa padrão da playlist" },
        iconFraction = 0.39f,
    )
}
