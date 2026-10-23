package com.music.bitchord.data.catalog.provider.opensubsonic

import com.music.bitchord.data.catalog.provider.ProviderHealth
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import java.util.UUID

internal enum class OpenSubsonicAuthMode {
    API_KEY,
    PASSWORD,
}

/**
 * Persisted account metadata. Credentials deliberately cannot fit in this
 * shape: they live behind [OpenSubsonicCredentialStore].
 */
internal data class OpenSubsonicAccountRecord(
    val accountId: String,
    val serverUrl: String,
    val authMode: OpenSubsonicAuthMode,
    val username: String = "",
    val ratingScopeId: String = "",
    val lastHealthAtEpochSeconds: Long? = null,
    val lastHealthReachable: Boolean? = null,
    val lastServerType: String? = null,
    val lastServerVersion: String? = null,
    val lastSyncAtEpochSeconds: Long? = null,
    val lastSyncSucceeded: Boolean? = null,
    val lastSyncSummary: String? = null,
)

/**
 * Editor state. `secretReplacement == null` means "keep the stored credential"
 * when the server and authentication mode are unchanged; a stored secret is
 * never copied back into this object.
 */
internal data class OpenSubsonicAccountDraft(
    val serverUrl: String,
    val authMode: OpenSubsonicAuthMode,
    val username: String = "",
    val secretReplacement: String? = null,
)

internal interface OpenSubsonicAccountPersistence {
    fun read(): OpenSubsonicAccountRecord?
    fun write(record: OpenSubsonicAccountRecord)
    fun clear()
}

internal interface OpenSubsonicCredentialStore {
    fun read(accountId: String): String?
    fun write(accountId: String, credential: String)
    fun clear(accountId: String)
}

/**
 * Provider-specific account boundary kept intentionally smaller than a generic
 * remote-account framework. It owns account identity and credential separation;
 * networking and Room sync stay in their existing boundaries.
 */
internal class OpenSubsonicAccountRepository(
    private val persistence: OpenSubsonicAccountPersistence,
    private val credentials: OpenSubsonicCredentialStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun current(): OpenSubsonicAccountRecord? = persistence.read()

    fun hasCredential(): Boolean {
        val account = current() ?: return false
        return !credentials.read(account.accountId).isNullOrEmpty()
    }

    fun save(draft: OpenSubsonicAccountDraft): OpenSubsonicAccountRecord {
        val existing = current()
        val accountId = existing?.accountId ?: idFactory()
        val credential = credentialForDraft(existing, draft)
        val config = configFor(accountId, draft, credential)
        val normalizedCredential = credentialFrom(config.auth)
        val username = (config.auth as? OpenSubsonicAuth.Password)?.username.orEmpty()
        val record = OpenSubsonicAccountRecord(
            accountId = accountId,
            serverUrl = config.normalizedServerUrl,
            authMode = draft.authMode,
            username = username,
            ratingScopeId = UUID.randomUUID().toString(),
            lastHealthAtEpochSeconds = existing?.lastHealthAtEpochSeconds,
            lastHealthReachable = existing?.lastHealthReachable,
            lastServerType = existing?.lastServerType,
            lastServerVersion = existing?.lastServerVersion,
            lastSyncAtEpochSeconds = existing?.lastSyncAtEpochSeconds,
            lastSyncSucceeded = existing?.lastSyncSucceeded,
            lastSyncSummary = existing?.lastSyncSummary,
        )

        // Validate everything before either store changes. Invalidate active
        // stream leases *before* overwriting credentials, even if persistence
        // subsequently fails. Cached catalog rows are intentionally untouched.
        OpenSubsonicAccountRevisionClock.revoke()
        credentials.write(accountId, normalizedCredential)
        persistence.write(record)
        return record
    }

    /** Builds a provider for Test connection without persisting editor changes. */
    fun providerForDraft(draft: OpenSubsonicAccountDraft): OpenSubsonicProvider {
        val existing = current()
        val accountId = existing?.accountId ?: PREVIEW_ACCOUNT_ID
        val credential = credentialForDraft(existing, draft)
        return OpenSubsonicProvider(configFor(accountId, draft, credential))
    }

    fun savedConfig(): OpenSubsonicAccountConfig? {
        val account = current() ?: return null
        val credential = credentials.read(account.accountId)?.takeIf(String::isNotEmpty) ?: return null
        return configFor(
            accountId = account.accountId,
            draft = OpenSubsonicAccountDraft(
                serverUrl = account.serverUrl,
                authMode = account.authMode,
                username = account.username,
                secretReplacement = credential,
            ),
            credential = credential,
        ).copy(ratingScopeId = account.ratingScopeId)
    }

    fun savedProvider(): OpenSubsonicProvider? = savedConfig()?.let(::OpenSubsonicProvider)

    fun recordHealth(health: ProviderHealth, atEpochSeconds: Long) {
        val account = current() ?: return
        persistence.write(
            account.copy(
                lastHealthAtEpochSeconds = atEpochSeconds,
                lastHealthReachable = health.reachable,
                lastServerType = health.serverType ?: account.lastServerType,
                lastServerVersion = health.serverVersion ?: account.lastServerVersion,
            ),
        )
    }

    fun recordHealthFailure(atEpochSeconds: Long) {
        val account = current() ?: return
        persistence.write(
            account.copy(
                lastHealthAtEpochSeconds = atEpochSeconds,
                lastHealthReachable = false,
            ),
        )
    }

    fun recordSync(result: ProviderSyncResult, atEpochSeconds: Long) {
        val account = current() ?: return
        val succeeded = result is ProviderSyncResult.Success
        val summary = when (result) {
            is ProviderSyncResult.Success -> buildString {
                append("Synced ${result.syncedAssets} tracks")
                append(" · removed ${result.removedAssets}")
                append(" · ${result.identityConflicts} identity conflict")
                if (result.identityConflicts != 1) append('s')
            }
            is ProviderSyncResult.Failure -> "Sync failed"
        }
        persistence.write(
            account.copy(
                lastSyncAtEpochSeconds = atEpochSeconds,
                lastSyncSucceeded = succeeded,
                lastSyncSummary = summary,
            ),
        )
    }

    fun forget() {
        // Revoke in-flight HTTP/Media3 leases before removing secrets.
        OpenSubsonicAccountRevisionClock.revoke()
        val accountId = current()?.accountId
        try {
            if (accountId != null) credentials.clear(accountId)
        } finally {
            persistence.clear()
        }
    }

    private fun credentialForDraft(
        existing: OpenSubsonicAccountRecord?,
        draft: OpenSubsonicAccountDraft,
    ): String {
        val changedMode = existing != null && existing.authMode != draft.authMode
        val changedServer = existing != null && existing.serverUrl != normalizedServerUrl(draft.serverUrl)
        val credentialContextChanged = changedMode || changedServer
        if (credentialContextChanged && draft.secretReplacement == null) {
            throw IllegalArgumentException(
                "Enter a new credential when changing the server or authentication mode",
            )
        }
        val raw = draft.secretReplacement
            ?: existing?.takeIf { !credentialContextChanged }?.let { credentials.read(it.accountId) }
            ?: throw IllegalArgumentException("OpenSubsonic credential is required")
        return when (draft.authMode) {
            OpenSubsonicAuthMode.API_KEY -> raw.trim().also {
                require(it.isNotEmpty()) { "OpenSubsonic API key must not be blank" }
            }
            OpenSubsonicAuthMode.PASSWORD -> raw.also {
                require(it.isNotEmpty()) { "OpenSubsonic password must not be empty" }
            }
        }
    }

    private fun configFor(
        accountId: String,
        draft: OpenSubsonicAccountDraft,
        credential: String,
    ): OpenSubsonicAccountConfig {
        val auth = when (draft.authMode) {
            OpenSubsonicAuthMode.API_KEY -> OpenSubsonicAuth.ApiKey(credential.trim())
            OpenSubsonicAuthMode.PASSWORD -> OpenSubsonicAuth.Password(
                username = draft.username.trim(),
                password = credential,
            )
        }
        return OpenSubsonicAccountConfig(
            accountId = accountId,
            serverUrl = draft.serverUrl,
            auth = auth,
        )
    }

    private fun credentialFrom(auth: OpenSubsonicAuth): String = when (auth) {
        is OpenSubsonicAuth.ApiKey -> auth.value
        is OpenSubsonicAuth.Password -> auth.password
    }

    private fun normalizedServerUrl(value: String): String = value.trim().trimEnd('/')

    private companion object {
        const val PREVIEW_ACCOUNT_ID = "preview"
    }
}
