package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsGroupCorroborator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Never delay first album paint or inflate BitChordApp's Compose method.
 * Opening a real catalog ReleaseGroup launches an optional bounded read-only
 * corroboration of alternative official editions on an IO dispatcher.
 */
@Composable
internal fun DiscogsGroupCorroborationEffect(
    browseId: String,
    releaseGroupMbid: String?,
    selectedReleaseMbid: String?,
) {
    LaunchedEffect(browseId, releaseGroupMbid, selectedReleaseMbid) {
        if (releaseGroupMbid == null || selectedReleaseMbid == null ||
            browseId != "catalog:RELEASE_GROUP:$releaseGroupMbid"
        ) return@LaunchedEffect
        delay(350)
        withContext(Dispatchers.IO) {
            DiscogsGroupCorroborator.inspect(releaseGroupMbid, selectedReleaseMbid)
        }
    }
}
