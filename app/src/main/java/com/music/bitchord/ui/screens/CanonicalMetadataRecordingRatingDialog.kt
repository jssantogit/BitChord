package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.listening.ListeningEventDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One native first-party recording rating editor for trusted MusicBrainz
 * metadata, including recordings without an audio asset. No play capability
 * follows from recording a preference. */
@Composable
internal fun CanonicalMetadataRecordingRatingDialog(
    recordingId: String,
    onDismiss: () -> Unit,
) {
    if (!isVerifiedCatalogRecordingId(recordingId)) return
    val context = LocalContext.current.applicationContext
    val dao = remember(context) { ListeningEventDatabase.get(context).recordingRatingDao() }
    val current by remember(dao, recordingId) { dao.observeCurrent(recordingId) }
        .collectAsState(initial = null)
    var saving by remember(recordingId) { mutableStateOf(false) }
    var denied by remember(recordingId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    CanonicalRecordingFeedbackPanel(
        rating = current?.ratingValue,
        title = "Avaliar faixa",
        saving = saving,
        denied = denied,
        onDismiss = { if (!saving) onDismiss() },
        onSave = { value ->
            if (!saving) {
                saving = true
                denied = false
                scope.launch {
                    val ok = try {
                        if (!isVerifiedCatalogRecordingId(recordingId) ||
                            (value != 0.0 && !isValidCanonicalEntityRating(value))) false
                        else withContext(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            if (value == 0.0) dao.clearRating(recordingId, now)
                            else dao.setRating(recordingId, value, now)
                            true
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { false }
                    saving = false
                    if (ok) onDismiss() else denied = true
                }
            }
        },
    )
}
