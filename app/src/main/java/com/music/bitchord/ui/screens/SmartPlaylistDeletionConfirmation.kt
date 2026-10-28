package com.music.bitchord.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Prevent accidental permanent deletion from the smart library. */
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
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Excluir $noun?") },
        text = { Text("Excluir \"$name\" do dispositivo? $warning") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Excluir definitivamente") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
    )
}
