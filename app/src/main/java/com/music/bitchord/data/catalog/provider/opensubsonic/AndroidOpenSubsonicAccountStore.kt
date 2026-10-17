package com.music.bitchord.data.catalog.provider.opensubsonic

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class SharedPrefsOpenSubsonicAccountPersistence(
    context: Context,
) : OpenSubsonicAccountPersistence {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): OpenSubsonicAccountRecord? {
        val accountId = prefs.getString(KEY_ACCOUNT_ID, null)?.takeIf(String::isNotBlank) ?: return null
        val serverUrl = prefs.getString(KEY_SERVER_URL, null)?.takeIf(String::isNotBlank) ?: return null
        val authMode = prefs.getString(KEY_AUTH_MODE, null)
            ?.let { saved -> OpenSubsonicAuthMode.entries.firstOrNull { it.name == saved } }
            ?: return null
        return OpenSubsonicAccountRecord(
            accountId = accountId,
            serverUrl = serverUrl,
            authMode = authMode,
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            lastHealthAtEpochSeconds = prefs.longOrNull(KEY_LAST_HEALTH_AT),
            lastHealthReachable = prefs.booleanOrNull(KEY_LAST_HEALTH_REACHABLE),
            lastServerType = prefs.getString(KEY_LAST_SERVER_TYPE, null),
            lastServerVersion = prefs.getString(KEY_LAST_SERVER_VERSION, null),
            lastSyncAtEpochSeconds = prefs.longOrNull(KEY_LAST_SYNC_AT),
            lastSyncSucceeded = prefs.booleanOrNull(KEY_LAST_SYNC_SUCCEEDED),
            lastSyncSummary = prefs.getString(KEY_LAST_SYNC_SUMMARY, null),
        )
    }

    override fun write(record: OpenSubsonicAccountRecord) {
        prefs.edit().clear()
            .putString(KEY_ACCOUNT_ID, record.accountId)
            .putString(KEY_SERVER_URL, record.serverUrl)
            .putString(KEY_AUTH_MODE, record.authMode.name)
            .putString(KEY_USERNAME, record.username)
            .putNullableLong(KEY_LAST_HEALTH_AT, record.lastHealthAtEpochSeconds)
            .putNullableBoolean(KEY_LAST_HEALTH_REACHABLE, record.lastHealthReachable)
            .putNullableString(KEY_LAST_SERVER_TYPE, record.lastServerType)
            .putNullableString(KEY_LAST_SERVER_VERSION, record.lastServerVersion)
            .putNullableLong(KEY_LAST_SYNC_AT, record.lastSyncAtEpochSeconds)
            .putNullableBoolean(KEY_LAST_SYNC_SUCCEEDED, record.lastSyncSucceeded)
            .putNullableString(KEY_LAST_SYNC_SUMMARY, record.lastSyncSummary)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun SharedPreferences.longOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun SharedPreferences.booleanOrNull(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null

    private fun SharedPreferences.Editor.putNullableLong(key: String, value: Long?): SharedPreferences.Editor =
        if (value == null) remove(key) else putLong(key, value)

    private fun SharedPreferences.Editor.putNullableBoolean(key: String, value: Boolean?): SharedPreferences.Editor =
        if (value == null) remove(key) else putBoolean(key, value)

    private fun SharedPreferences.Editor.putNullableString(key: String, value: String?): SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, value)

    private companion object {
        const val PREFS_NAME = "bitchord_opensubsonic_account"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_AUTH_MODE = "auth_mode"
        const val KEY_USERNAME = "username"
        const val KEY_LAST_HEALTH_AT = "last_health_at"
        const val KEY_LAST_HEALTH_REACHABLE = "last_health_reachable"
        const val KEY_LAST_SERVER_TYPE = "last_server_type"
        const val KEY_LAST_SERVER_VERSION = "last_server_version"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_SYNC_SUCCEEDED = "last_sync_succeeded"
        const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"
    }
}

/**
 * Credential ciphertext backed by a non-exportable Android Keystore AES key.
 * There is intentionally no plaintext fallback: if this cannot be decrypted,
 * the account remains configured but requires credential re-entry.
 */
internal class AndroidKeystoreOpenSubsonicCredentialStore(
    context: Context,
) : OpenSubsonicCredentialStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        SECRET_PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(accountId: String): String? {
        val ivRaw = prefs.getString(ivKey(accountId), null) ?: return null
        val ciphertextRaw = prefs.getString(ciphertextKey(accountId), null) ?: return null
        return try {
            val iv = Base64.getDecoder().decode(ivRaw)
            val ciphertext = Base64.getDecoder().decode(ciphertextRaw)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Throwable) {
            // A restored/corrupt ciphertext or invalidated key cannot be made
            // usable by retrying. Forget only credential material; the plain
            // account metadata stays so the editor can ask for a replacement.
            clear(accountId)
            resetKey()
            null
        }
    }

    override fun write(accountId: String, credential: String) {
        require(credential.isNotEmpty()) { "OpenSubsonic credential must not be empty" }
        val encrypted = runCatching { encrypt(credential) }.getOrElse {
            resetKey()
            runCatching { encrypt(credential) }.getOrElse {
                throw IllegalStateException("OpenSubsonic credential storage unavailable")
            }
        }
        val committed = prefs.edit()
            .putString(ivKey(accountId), Base64.getEncoder().encodeToString(encrypted.first))
            .putString(ciphertextKey(accountId), Base64.getEncoder().encodeToString(encrypted.second))
            .commit()
        if (!committed) throw IllegalStateException("OpenSubsonic credential storage unavailable")
    }

    override fun clear(accountId: String) {
        prefs.edit()
            .remove(ivKey(accountId))
            .remove(ciphertextKey(accountId))
            .apply()
    }

    private fun encrypt(value: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv to cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun resetKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun ivKey(accountId: String) = "credential.$accountId.iv"
    private fun ciphertextKey(accountId: String) = "credential.$accountId.ciphertext"

    private companion object {
        const val SECRET_PREFS_NAME = "bitchord_opensubsonic_secret"
        const val KEY_ALIAS = "bitchord_opensubsonic_secret_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Android-facing state holder used by Settings/Sources UI. */
internal class AndroidOpenSubsonicAccountSession(
    context: Context,
) {
    private val repository = OpenSubsonicAccountRepository(
        persistence = SharedPrefsOpenSubsonicAccountPersistence(context),
        credentials = AndroidKeystoreOpenSubsonicCredentialStore(context),
    )
    private val controller = OpenSubsonicAccountController(
        repository = repository,
        operations = AndroidOpenSubsonicAccountOperations(context),
    )
    private val mutableAccount = MutableStateFlow(repository.current())
    private val mutableHasCredential = MutableStateFlow(repository.hasCredential())

    val account: StateFlow<OpenSubsonicAccountRecord?> = mutableAccount
    val hasCredential: StateFlow<Boolean> = mutableHasCredential

    fun save(draft: OpenSubsonicAccountDraft): Result<OpenSubsonicAccountRecord> =
        runCatching { repository.save(draft) }.also { refresh() }

    fun forget() {
        repository.forget()
        refresh()
    }

    suspend fun testConnection(draft: OpenSubsonicAccountDraft): Result<OpenSubsonicConnectionReport> =
        controller.testConnection(draft)

    suspend fun testSavedConnection(): Result<OpenSubsonicConnectionReport> =
        controller.testSavedConnection().also { refresh() }

    suspend fun syncNow(): ProviderSyncResult = controller.syncNow().also { refresh() }

    private fun refresh() {
        mutableAccount.value = repository.current()
        mutableHasCredential.value = repository.hasCredential()
    }
}
