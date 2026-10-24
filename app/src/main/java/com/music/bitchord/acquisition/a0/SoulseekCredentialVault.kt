package com.music.bitchord.acquisition.a0

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Only account secrets live here. No login on startup and no long-lived socket.
 * The encrypted file lives in noBackupFilesDir; the AES key never leaves Keystore.
 * A lost/invalidated key fails closed. A new account replaces the previous blob.
 */
internal class SoulseekCredentialVault(context: Context) {
    internal class Credentials(val username: String, val password: CharArray) {
        fun erase() = password.fill('\u0000')
    }

    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))

    @Synchronized fun read(): Credentials? = runCatching {
        if (!file.baseFile.exists()) return null
        val key = key(create = false) ?: return null
        val encrypted = file.openRead().use { it.readBytes() }
        if (encrypted.size !in 29..4096) return null
        val ivSize = encrypted[0].toInt() and 255
        if (ivSize != 12 || encrypted.size < ivSize + 18) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted, 1, ivSize))
        cipher.updateAAD(AAD)
        val plain = cipher.doFinal(encrypted, 1 + ivSize, encrypted.size - ivSize - 1)
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                val userLength = input.readUnsignedShort()
                if (userLength !in 1..256) return null
                val userBytes = ByteArray(userLength).also { input.readFully(it) }
                val passLength = input.readUnsignedShort()
                if (passLength !in 1..1024) return null
                val passBytes = ByteArray(passLength).also { input.readFully(it) }
                if (input.read() != -1) return null
                try {
                    val username = String(userBytes, Charsets.UTF_8)
                    val chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(passBytes))
                    val password = CharArray(chars.remaining()).also { chars.get(it) }
                    Credentials(username, password)
                } finally {
                    userBytes.fill(0)
                    passBytes.fill(0)
                }
            }
        } finally { plain.fill(0) }
    }.getOrNull()

    @Synchronized fun save(username: String, password: CharArray) {
        require(username.isNotBlank() && username.toByteArray(Charsets.UTF_8).size <= 256)
        require(password.isNotEmpty() && password.size <= 256)
        val userBytes = username.toByteArray(Charsets.UTF_8)
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password))
        val passBytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        val plaintext = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeShort(userBytes.size)
                out.write(userBytes)
                out.writeShort(passBytes.size)
                out.write(passBytes)
            }
            bytes.toByteArray()
        }
        userBytes.fill(0)
        passBytes.fill(0)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key(create = true)))
            cipher.updateAAD(AAD)
            val ciphertext = cipher.doFinal(plaintext)
            val output = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
            val stream = file.startWrite()
            try {
                stream.write(output)
                file.finishWrite(stream)
            } catch (failure: Throwable) {
                file.failWrite(stream)
                throw failure
            } finally {
                output.fill(0)
                ciphertext.fill(0)
            }
        } finally { plaintext.fill(0) }
    }

    @Synchronized fun forget() {
        file.delete()
        // Rotate the vault key on logout so previous ciphertext is unrecoverable.
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(ALIAS)
        }
    }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "bitchord.soulseek.account.aes.v1"
        const val FILE_NAME = "soulseek-account-v1.bin"
        val AAD = "BitChord:exclusive-dev-soulseek-account:v1".toByteArray(Charsets.UTF_8)
    }
}

/** Explicit one-shot login test. The server uses plaintext TCP; never silently retry. */
internal object SoulseekAccountProbe {
    suspend fun test(username: String, secret: CharArray): Boolean = withContext(Dispatchers.IO) {
        var packet: ByteArray? = null
        try {
            packet = A0ExperimentalLoginCodec.encode(username, secret)
            Socket().use { socket ->
                socket.connect(InetSocketAddress("server.slsknet.org", 2416), 7000)
                socket.soTimeout = 12000
                socket.getOutputStream().apply {
                    write(checkNotNull(packet))
                    flush()
                }
                checkNotNull(packet).fill(0)
                packet = null
                A0ExperimentalLoginCodec.readResponse(socket.getInputStream())
            }
        } catch (_: Exception) {
            // No transport exception or server response ever includes credentials in UI/logs.
            false
        } finally {
            packet?.fill(0)
        }
    }
}
