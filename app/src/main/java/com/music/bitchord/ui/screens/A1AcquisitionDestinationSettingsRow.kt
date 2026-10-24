package com.music.bitchord.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.download.A1AndroidDownloadDestination
import com.music.bitchord.download.A1DownloadDestinationPolicy

/**
 * Settings-only A1 UI; deliberately not wired into the existing download
 * engine, A0 scratch, or canonical catalog until A2/A4 are validated.
 */
@Composable
internal fun A1AcquisitionDestinationSettingsRow() {
    val context=LocalContext.current
    var destination by remember(context) {
        mutableStateOf(A1AndroidDownloadDestination.current(context))
    }
    var failed by remember { mutableStateOf(false) }
    val picker=rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if(uri != null) {
            failed=!A1AndroidDownloadDestination.select(context,uri)
            destination=A1AndroidDownloadDestination.current(context)
        }
    }
    val selected=(destination as? A1DownloadDestinationPolicy.Resolution.Ready)?.target
    val summary=when {
        failed -> stringResource(R.string.acquisition_destination_failure)
        destination is A1DownloadDestinationPolicy.Resolution.NeedsReselection ->
            stringResource(R.string.acquisition_destination_revoked)
        selected is A1DownloadDestinationPolicy.Target.GrantedTree ->
            stringResource(R.string.acquisition_destination_chosen)
        else -> stringResource(R.string.acquisition_destination_default)
    }
    SettingsRow(
        icon=Icons.Rounded.Folder,
        title=stringResource(R.string.acquisition_destination_title),
        subtitle=summary,
        onClick={ picker.launch(null) },
    )
    if(selected is A1DownloadDestinationPolicy.Target.GrantedTree ||
        destination is A1DownloadDestinationPolicy.Resolution.NeedsReselection
    ) {
        SettingsRow(
            icon=Icons.Rounded.LibraryMusic,
            title=stringResource(R.string.acquisition_destination_reset),
            subtitle=stringResource(R.string.acquisition_destination_reset_subtitle),
            onClick={
                failed=!A1AndroidDownloadDestination.reset(context)
                destination=A1AndroidDownloadDestination.current(context)
            }
        )
    }
}
