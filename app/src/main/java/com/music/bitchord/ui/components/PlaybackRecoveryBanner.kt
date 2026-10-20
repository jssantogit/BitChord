package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.playback.PlaybackRecoveryNotice
import com.music.bitchord.playback.PlaybackRecoveryReason
import com.music.bitchord.playback.PlaybackRecoveryStatus

/**
 * Does not describe server health as confirmed outage; a transport failure is
 * merely evidence that this rendition could not be opened on this attempt.
 * The only retry affordance is an actual user click, never a network callback.
 */
@Composable
internal fun PlaybackRecoveryBanner(
    notice: PlaybackRecoveryNotice,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    retryEnabled: Boolean = true,
) {
    val message = when (notice.reason) {
        PlaybackRecoveryReason.CONNECTION_OR_SERVER -> R.string.playback_recovery_connection
        PlaybackRecoveryReason.REMOTE_UNAVAILABLE -> R.string.playback_recovery_unavailable
        PlaybackRecoveryReason.REMOTE_OPEN_FAILED -> R.string.playback_recovery_failed
    }
    Surface(
        modifier = modifier.widthIn(max = 430.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 5.dp,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 5.dp)) {
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.playback_recovery_dismiss))
                }
                TextButton(
                    onClick = onRetry,
                    enabled = retryEnabled &&
                        notice.status == PlaybackRecoveryStatus.WAITING_FOR_USER,
                ) {
                    Text(
                        stringResource(
                            if (notice.status == PlaybackRecoveryStatus.RETRYING)
                                R.string.playback_recovery_retrying
                            else R.string.playback_recovery_retry,
                        ),
                    )
                }
            }
        }
    }
}
