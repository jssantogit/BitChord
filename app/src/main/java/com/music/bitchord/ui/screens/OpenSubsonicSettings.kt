package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.ui.components.EditorField
import com.music.bitchord.ui.components.ServerEditorAlert
import com.music.bitchord.ui.components.NativeAlertHost
import com.music.bitchord.ui.components.ConfirmationAlert
import com.music.bitchord.ui.components.NativeAlertHost
import com.music.bitchord.data.catalog.provider.opensubsonic.AndroidOpenSubsonicAccountSession
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountDraft
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicConnectionReport
import kotlinx.coroutines.launch

/**
 * The OpenSubsonic/Navidrome account is a remote-library connection, not a
 * playback-source priority. It therefore lives beside WebDAV/SMB and only
 * exposes catalog sync here; PlaybackResolver integration is a later slice.
 */
@Composable
internal fun OpenSubsonicRemoteLibrarySection() {
    val context = LocalContext.current
    val session = remember(context.applicationContext) {
        AndroidOpenSubsonicAccountSession(context.applicationContext)
    }
    val account by session.account.collectAsStateWithLifecycle()
    val hasCredential by session.hasCredential.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }

    SettingsGroup(
        header = stringResource(R.string.opensubsonic_title),
        footer = stringResource(R.string.opensubsonic_description),
    ) {
        SettingsRow(
            icon = Icons.Rounded.Cloud,
            title = stringResource(R.string.opensubsonic_title),
            subtitle = account?.displaySubtitle()
                ?: stringResource(R.string.opensubsonic_not_configured),
            value = stringResource(if (account == null) R.string.opensubsonic_set_up else R.string.opensubsonic_edit),
            onClick = { editing = true },
        )

        if (account != null) {
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Dns,
                title = stringResource(if (testing) R.string.testing else R.string.opensubsonic_test_connection),
                subtitle = when {
                    !hasCredential -> stringResource(R.string.opensubsonic_credential_missing)
                    connectionStatus != null -> connectionStatus
                    account?.lastHealthReachable == true -> account?.healthSubtitle(context)
                    account?.lastHealthReachable == false -> stringResource(R.string.opensubsonic_connection_failed)
                    else -> stringResource(R.string.opensubsonic_test_connection_subtitle)
                },
                enabled = !testing && !syncing,
                onClick = {
                    if (!hasCredential) {
                        editing = true
                        return@SettingsRow
                    }
                    testing = true
                    connectionStatus = null
                    scope.launch {
                        session.testSavedConnection().fold(
                            onSuccess = { report -> connectionStatus = report.connectedLabel(context) },
                            onFailure = { connectionStatus = context.getString(R.string.opensubsonic_connection_failed) },
                        )
                        testing = false
                    }
                },
            )

            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Sync,
                title = stringResource(if (syncing) R.string.opensubsonic_syncing else R.string.opensubsonic_sync_now),
                subtitle = account?.lastSyncSummary
                    ?: stringResource(R.string.opensubsonic_sync_subtitle),
                enabled = !testing && !syncing,
                onClick = {
                    if (!hasCredential) {
                        editing = true
                        return@SettingsRow
                    }
                    syncing = true
                    scope.launch {
                        // The session persists and refreshes a safe sync summary.
                        // Keep that state on the sync row instead of overwriting
                        // the connection-test row with an unrelated message.
                        session.syncNow()
                        syncing = false
                    }
                },
            )

            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.DeleteSweep,
                title = stringResource(R.string.opensubsonic_forget),
                subtitle = stringResource(R.string.opensubsonic_forget_subtitle),
                enabled = !testing && !syncing,
                onClick = { confirmForget = true },
            )
        }
    }

    if (editing) {
        OpenSubsonicAccountEditor(
            account = account,
            hasCredential = hasCredential,
            session = session,
            onDismiss = { editing = false },
        )
    }

    if (confirmForget) {
        NativeAlertHost(onDismiss = { confirmForget = false }) {
            ConfirmationAlert(
                hazeState = null,
                title = stringResource(R.string.opensubsonic_forget_confirm_title),
                description = stringResource(R.string.opensubsonic_forget_confirm_text),
                confirmLabel = stringResource(R.string.opensubsonic_forget),
                destructive = true,
                onConfirm = {
                    session.forget()
                    connectionStatus = null
                    confirmForget = false
                },
                onDismiss = { confirmForget = false },
            )
        }
    }
}

@Composable
private fun OpenSubsonicAccountEditor(
    account: OpenSubsonicAccountRecord?,
    hasCredential: Boolean,
    session: AndroidOpenSubsonicAccountSession,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember(account?.accountId) { mutableStateOf(account?.serverUrl.orEmpty()) }
    var authMode by remember(account?.accountId) {
        mutableStateOf(account?.authMode ?: OpenSubsonicAuthMode.API_KEY)
    }
    var username by remember(account?.accountId) { mutableStateOf(account?.username.orEmpty()) }
    var secretReplacement by remember(account?.accountId) { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    fun draft() = OpenSubsonicAccountDraft(
        serverUrl = serverUrl,
        authMode = authMode,
        username = username,
        secretReplacement = secretReplacement.takeIf(String::isNotEmpty),
    )

    val modeChanged = account != null && authMode != account.authMode
    val serverChanged = account != null &&
        serverUrl.trim().trimEnd('/') != account.serverUrl
    val credentialCanBeKept = hasCredential && !modeChanged && !serverChanged

    NativeAlertHost(onDismiss = { if (!testing) onDismiss() }) {
        ServerEditorAlert(
            hazeState = null,
            title = stringResource(R.string.opensubsonic_editor_title),
            description = stringResource(R.string.opensubsonic_editor_description),
            fields = listOfNotNull(
                EditorField(
                    value = serverUrl,
                    onChange = { serverUrl = it; status = null },
                    placeholder = stringResource(R.string.opensubsonic_server_url),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                ),
                if (authMode == OpenSubsonicAuthMode.PASSWORD) {
                    EditorField(
                        value = username,
                        onChange = { username = it; status = null },
                        placeholder = stringResource(R.string.username),
                    )
                } else null,
                EditorField(
                    value = secretReplacement,
                    onChange = { secretReplacement = it; status = null },
                    placeholder = stringResource(
                        if (authMode == OpenSubsonicAuthMode.API_KEY)
                            R.string.opensubsonic_api_key
                        else R.string.password,
                    ),
                    isPassword = true,
                ),
            ),
            afterFirstField = {
                Text(
                    stringResource(R.string.opensubsonic_authentication),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.opensubsonic_api_key),
                        stringResource(R.string.password),
                    ),
                    selectedIndex = if (authMode == OpenSubsonicAuthMode.API_KEY) 0 else 1,
                    onSelect = {
                        authMode = if (it == 0) OpenSubsonicAuthMode.API_KEY
                            else OpenSubsonicAuthMode.PASSWORD
                        secretReplacement = ""
                        status = null
                    },
                    enabled = !testing,
                )
            },
            afterFields = {
                Text(
                    if (credentialCanBeKept && secretReplacement.isEmpty())
                        stringResource(R.string.opensubsonic_credential_kept)
                    else stringResource(R.string.opensubsonic_credential_change_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            status = status,
            statusIsGood = !statusIsError,
            testing = testing,
            canSubmit = serverUrl.isNotBlank(),
            onSave = {
                val saved = session.save(draft())
                if (saved.isSuccess) {
                    onDismiss()
                } else {
                    statusIsError = true
                    status = saved.exceptionOrNull()?.message
                        ?: context.getString(R.string.opensubsonic_save_failed)
                }
            },
            onTest = {
                testing = true
                status = null
                scope.launch {
                    session.testConnection(draft()).fold(
                        onSuccess = { report ->
                            statusIsError = false
                            status = report.connectedLabel(context)
                        },
                        onFailure = { failure ->
                            statusIsError = true
                            status = failure.message
                                ?: context.getString(R.string.opensubsonic_connection_failed)
                        },
                    )
                    testing = false
                }
            },
            onDismiss = onDismiss,
        )
    }
}

private fun OpenSubsonicAccountRecord.displaySubtitle(): String = when {
    username.isNotBlank() -> "$serverUrl · $username"
    else -> serverUrl
}

private fun OpenSubsonicAccountRecord.healthSubtitle(context: android.content.Context): String =
    listOfNotNull(
        lastServerType?.takeIf(String::isNotBlank),
        lastServerVersion?.takeIf(String::isNotBlank),
    ).joinToString(" · ").ifBlank { context.getString(R.string.connected) }

private fun OpenSubsonicConnectionReport.connectedLabel(context: android.content.Context): String =
    listOfNotNull(
        context.getString(R.string.connected),
        health.serverType?.takeIf(String::isNotBlank),
        health.serverVersion?.takeIf(String::isNotBlank),
        health.protocolVersion?.takeIf(String::isNotBlank),
    ).joinToString(" · ")
