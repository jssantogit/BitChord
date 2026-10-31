package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import com.music.bitchord.ui.components.ConfirmationAlert

/** Use the same native warning shell as the other BitChord confirmations. */
@Composable
internal fun SmartPlaylistDeletionConfirmation(
    name: String,
    isFilter: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val noun = if (isFilter) "filtro" else "playlist"
    val warning = if (isFilter) "O filtro salvo será removido."
        else "Playlists dependentes podem impedir esta exclusão."
    BackHandler { onCancel() }
    ConfirmationAlert(
        hazeState = null,
        title = "Excluir $noun?",
        description = "Excluir \"$name\" do dispositivo? $warning",
        confirmLabel = "Excluir definitivamente",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onCancel,
    )
}
