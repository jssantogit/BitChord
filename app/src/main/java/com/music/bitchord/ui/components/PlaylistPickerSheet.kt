package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import com.music.bitchord.ui.theme.appleMusicKeyColor
import com.music.bitchord.ui.theme.appleSelectionAccentColor
import java.util.Locale

/**
 * Where a track goes: one of the account's playlists, or a new one.
 *
 * Two panels in one sheet rather than a sheet that opens a dialog. Creating a
 * playlist here is nearly always in service of adding the track that opened
 * this — so the form comes back to the same place, and the create request
 * carries the track with it instead of leaving a new empty playlist behind
 * for the user to add to a second time.
 *
 * [song] is null when the flow started from the Library tab rather than from a
 * track, which is the one case where the header has no track to draw and
 * "New playlist" is the whole point of the sheet.
 *
 * Playlists are ticked rather than picked: one track often belongs in more
 * than one list, and a sheet that closed on the first tap meant opening it
 * again for each. [onAdd] gets every ticked playlist at once.
 */
@Composable
fun PlaylistPickerSheet(
    playlists: List<UserPlaylist>,
    loading: Boolean,
    onAdd: (List<UserPlaylist>) -> Unit,
    onCreate: (String, PlaylistPrivacy, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    song: Song? = null,
    startCreating: Boolean = false,
    onDraftChanged: (Boolean) -> Unit = {},
    onSavingChanged: (Boolean) -> Unit = {},
    onRequestDismiss: (() -> Unit)? = null,
) {
    var creating by remember { mutableStateOf(startCreating) }
    var formSaving by remember { mutableStateOf(false) }
    var formDirty by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // By id rather than by value: the list is re-fetched under an open sheet,
    // and a refreshed row must stay ticked.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // The picker and its creation form are two states of the same sheet.
    // Animate only the arriving state; keeping the outgoing form mounted
    // would compete with the new name field's automatic keyboard focus.
    val routeArrival = rememberRouteArrivalModifier(
        routeKey = if (creating) "playlist-create" else "playlist-picker",
        reduceMotion = com.music.bitchord.ui.LocalReduceAnimations.current,
        forward = creating,
    )

    if (creating) {
        NewPlaylistForm(
            // Nowhere to go back to when the sheet opened straight onto the
            // form; the sheet's own dismiss is the way out.
            onBack = if (startCreating) onRequestDismiss else ({
                if (formSaving) Unit
                else if (formDirty) confirmDiscard = true
                else { onDraftChanged(false); creating = false }
            }),
            onCreate = onCreate,
            onSavingChanged = { saving ->
                formSaving = saving
                onSavingChanged(saving)
            },
            onDraftChanged = { changed ->
                formDirty = changed
                onDraftChanged(changed)
            },
            modifier = modifier.then(routeArrival),
        )
        if (confirmDiscard && !formSaving) {
            ConfirmationAlert(
                hazeState = null,
                title = stringResource(R.string.editor_discard_title),
                description = stringResource(R.string.editor_discard_playlist_body),
                confirmLabel = stringResource(R.string.editor_discard_confirm),
                dismissLabel = stringResource(R.string.editor_keep_editing),
                destructive = true,
                onConfirm = {
                    confirmDiscard = false
                    formDirty = false
                    onDraftChanged(false)
                    creating = false
                },
                onDismiss = { confirmDiscard = false },
            )
        }
        return
    }

    Column(modifier.then(routeArrival).fillMaxWidth()) {
        if (song != null) {
            SheetTrackHeader(song)
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        }
        SheetHeading(
            stringResource(if (song != null) R.string.add_to_playlist else R.string.your_playlists)
                .uppercase(Locale.getDefault()),
        )

        ActionRow(
            icon = BitChordIcons.Plus,
            label = stringResource(R.string.new_playlist),
            onClick = { formDirty = false; onDraftChanged(false); creating = true },
        )

        when {
            // Only while there is nothing to show: re-fetching under a list
            // that is already up would replace it with a spinner for no gain.
            loading && playlists.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(26.dp),
                )
            }

            playlists.isEmpty() -> Text(
                text = stringResource(R.string.no_playlists_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            )

            else -> {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
                // Capped so a long list can't push the sheet past the screen;
                // it scrolls inside the sheet instead.
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { it.playlistId }) { playlist ->
                        val ticked = playlist.playlistId in selected
                        PlaylistRow(
                            playlist = playlist,
                            selected = ticked,
                            onClick = {
                                selected = if (ticked) selected - playlist.playlistId else selected + playlist.playlistId
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onAdd(playlists.filter { it.playlistId in selected }) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = appleMusicKeyColor(),
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) {
                    Text(
                        if (selected.isEmpty()) {
                            stringResource(R.string.add_to_playlist)
                        } else {
                            pluralStringResource(R.plurals.add_to_playlists_count, selected.size, selected.size)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlaylistRow(playlist: UserPlaylist, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Checkbox)
            .semantics { this.selected = selected }
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = playlist.thumbnailUrl.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(7.dp))
                .thumbnailBorder(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (playlist.subtitle.isNotBlank()) {
                Text(
                    text = playlist.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = if (selected) PlaylistEditorIcons.Selected else PlaylistEditorIcons.Unselected,
            contentDescription = null,
            tint = appleSelectionAccentColor(
                selected = selected,
                neutral = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.size(22.dp).testTag("playlist-picker-selection-icon"),
        )
    }
}

/**
 * Name and visibility, and nothing else. YouTube also takes a description,
 * which nobody fills in from a phone at the moment of saving a song.
 */
@Composable
private fun NewPlaylistForm(
    onBack: (() -> Unit)?,
    onCreate: (String, PlaylistPrivacy, (Boolean) -> Unit) -> Unit,
    onDraftChanged: (Boolean) -> Unit,
    onSavingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val nameLabel = stringResource(R.string.playlist_name)
    val nameError = when {
        name.length > 80 -> stringResource(R.string.playlist_editor_name_too_long)
        (attempted || name.isNotEmpty()) && name.isBlank() ->
            stringResource(R.string.playlist_editor_name_required)
        else -> null
    }
    val validName = name.isNotBlank() && name.length <= 80

    // Preserve the active input/IME; only an accepted save clears focus.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit: () -> Unit = {
        attempted = true
        if (validName && !saving) {
            saving = true
            onSavingChanged(true)
            saveFailed = false
            onCreate(name.trim(), privacy) { succeeded ->
                saving = false
                onSavingChanged(false)
                if (!succeeded) saveFailed = true
                else focusManager.clearFocus()
            }
        }
    }

    // As above: the keyboard is up from the moment this opens, and "Create
    // playlist" is below the fold without this.
    Column(
        modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (onBack != null) 8.dp else 22.dp, end = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let {
                IconButton(
                    onClick = it,
                    modifier = Modifier.size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                ) {
                    Icon(
                        PlaylistEditorIcons.Back,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.new_playlist),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.saved_to_youtube_music_account),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        Text(
            nameLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (name.isEmpty()) {
                    Text(
                        text = stringResource(R.string.playlist_name),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = {
                        if (!saving) {
                            name = it
                            saveFailed = false
                            onDraftChanged(it.isNotEmpty() || privacy != PlaylistPrivacy.PRIVATE)
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist-editor-create-name")
                        .semantics {
                            contentDescription = nameLabel
                            if (nameError != null) error(nameError)
                        }
                        .focusRequester(focusRequester),
                )
            }
            if (name.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !saving) {
                            name = ""
                            saveFailed = false
                            onDraftChanged(privacy != PlaylistPrivacy.PRIVATE)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PlaylistEditorIcons.Close,
                        contentDescription = stringResource(R.string.clear_name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (nameError != null) {
            Text(
                nameError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
        if (saveFailed) {
            Text(
                stringResource(R.string.playlist_editor_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 22.dp)
                    .testTag("playlist-editor-save-error")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        SheetHeading(stringResource(R.string.who_can_see_it).uppercase(Locale.getDefault()))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaylistPrivacy.entries.forEach { option ->
                PrivacyPill(
                    icon = option.icon,
                    label = option.label,
                    selected = option == privacy,
                    onClick = {
                        if (!saving) {
                            privacy = option
                            onDraftChanged(name.isNotEmpty() || option != PlaylistPrivacy.PRIVATE)
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = submit,
            enabled = validName && !saving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .height(50.dp),
            shape = RoundedCornerShape(25.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = appleMusicKeyColor(),
                contentColor = androidx.compose.ui.graphics.Color.White,
            ),
        ) {
            if (saving) CircularProgressIndicator(
                modifier = Modifier.size(20.dp).testTag("playlist-editor-saving"),
                strokeWidth = 2.dp,
                color = androidx.compose.ui.graphics.Color.White,
            )
            else Text(stringResource(R.string.create_playlist))
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The rename panel of [BrowseActionsSheet], which swaps itself out for this
 * rather than opening a dialog over itself — same reason the create form lives
 * inside [PlaylistPickerSheet].
 */
@Composable
internal fun RenamePlaylistForm(
    playlist: UserPlaylist,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDraftChanged: (Boolean) -> Unit = {},
) {
    var name by remember(playlist.playlistId) { mutableStateOf(playlist.title) }
    var confirmDiscard by remember(playlist.playlistId) { mutableStateOf(false) }
    var attempted by remember(playlist.playlistId) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val nameLabel = stringResource(R.string.playlist_name)
    val nameError = when {
        name.length > 80 -> stringResource(R.string.playlist_editor_name_too_long)
        (attempted || name.isNotEmpty()) && name.isBlank() ->
            stringResource(R.string.playlist_editor_name_required)
        else -> null
    }
    val hasChanges = name != playlist.title
    val validName = name.isNotBlank() && name.length <= 80

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val requestBack: () -> Unit = {
        if (hasChanges) confirmDiscard = true
        else { onDraftChanged(false); onBack() }
    }
    BackHandler { requestBack() }

    val submit: () -> Unit = {
        attempted = true
        if (validName && hasChanges) {
            focusManager.clearFocus()
            onDraftChanged(false)
            onRename(name.trim())
        }
    }

    // The form opens with the keyboard already up, which on a bottom sheet
    // would otherwise sit over the button the form exists to reach.
    Column(
        modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = requestBack,
                modifier = Modifier.size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            ) {
                Icon(
                    PlaylistEditorIcons.Back,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(R.string.rename_playlist),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        Text(
            nameLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = name,
                onValueChange = {
                    name = it
                    onDraftChanged(it != playlist.title)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("playlist-editor-rename-name")
                    .semantics {
                        contentDescription = nameLabel
                        if (nameError != null) error(nameError)
                    }
                    .focusRequester(focusRequester),
            )
        }
        if (nameError != null) {
            Text(
                nameError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
        Button(
            onClick = submit,
            enabled = validName && hasChanges,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .height(50.dp),
            shape = RoundedCornerShape(25.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = appleMusicKeyColor(),
                contentColor = androidx.compose.ui.graphics.Color.White,
            ),
        ) {
            Text(stringResource(R.string.save_name))
        }
        Spacer(Modifier.height(28.dp))
    }
    if (confirmDiscard) {
        ConfirmationAlert(
            hazeState = null,
            title = stringResource(R.string.editor_discard_title),
            description = stringResource(R.string.editor_discard_playlist_body),
            confirmLabel = stringResource(R.string.editor_discard_confirm),
            dismissLabel = stringResource(R.string.editor_keep_editing),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onDraftChanged(false)
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/**
 * The glyph that says what a visibility actually means — a padlock, a shared
 * link, a globe. Three words that all sound like degrees of the same thing
 * read much faster as three different shapes.
 */
private val PlaylistPrivacy.icon: ImageVector
    get() = when (this) {
        PlaylistPrivacy.PRIVATE -> PlaylistEditorIcons.Lock
        PlaylistPrivacy.UNLISTED -> PlaylistEditorIcons.Link
        PlaylistPrivacy.PUBLIC -> PlaylistEditorIcons.Globe
    }

/** The search filters' pill, carrying an icon ahead of its label. */
@Composable
private fun PrivacyPill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick, role = Role.RadioButton)
            .semantics { this.selected = selected }
            .padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
    }
}
