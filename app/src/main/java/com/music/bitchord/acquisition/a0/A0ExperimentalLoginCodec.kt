package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Isolated wire codec for a SINGLE, explicit, developer-only Soulseek login.
 *
 * No network, credential storage, logging, retries, background services or
 * file searches are implemented here. The official Soulseek server protocol
 * transmits plaintext username/password over TCP (NOT TLS); use only an
 * exclusive test account and never reuse its password for other services.
 *
 * Nicotine+ Soulseek Protocol Documentation (2026): major=177 is allocated to
 * experimental client development. The A0-specific minor "BC01" is only for
 * this experiment, not a registration or production client identity.
 *
 * Do not reuse our old 170/1201 localhost fixture (major 170 belongs to
 * Soulseek.NET clients, and minor 1201 collides with slskd reservations).
 */
internal object A0ExperimentalLoginCodec {
    const val EXPERIMENTAL_MAJOR = 177
    const val BITCHORD_MINOR = 0x42433031 // "BC01" experimental identifier
    private const val LOGIN_CODE = 1
    private const val MAX_CREDENTIAL_UTF8 = 256
    private const val MAX_RESPONSE_BYTES = 65536

    class LoginDenied : IOException("A0 test-only login was rejected")

    /** Returns a temporary byte array containing the plaintext password.
     * Caller MUST zero it after writing or any failure, and clear the password
     * input UI immediately. Immutable strings cannot be securely wiped in JVM.
     */
    fun encode(username: String, password: CharArray): ByteArray {
        val name = username.toByteArray(Charsets.UTF_8)
        require(name.isNotEmpty() && name.size <= MAX_CREDENTIAL_UTF8 &&
            !username.contains('\u0000') && username.isNotBlank()
        ) { "Invalid test-only username" }
        require(password.isNotEmpty() && password.size <= MAX_CREDENTIAL_UTF8 &&
            !password.contains('\u0000')
        ) { "Invalid test-only password" }

        // Soulseek's historical wire protocol requires the password as an
        // actual UTF-8 string; there is no TLS protection on this connection.
        val passString = String(password)
        val passBytes = passString.toByteArray(Charsets.UTF_8)
        require(passBytes.isNotEmpty() && passBytes.size <= MAX_CREDENTIAL_UTF8)
        val digest = MessageDigest.getInstance("MD5")
            .digest((username + passString).toByteArray(Charsets.UTF_8))
        val digestHex = digest.joinToString("") {
            "%02x".format(it.toInt() and 255)
        }

        try {
            val payload = ByteArrayOutputStream().apply {
                write(intLE(LOGIN_CODE))
                write(field(name))
                write(field(passBytes))
                write(intLE(EXPERIMENTAL_MAJOR))
                write(field(digestHex.toByteArray(Charsets.US_ASCII)))
                write(intLE(BITCHORD_MINOR))
            }.toByteArray()
            return intLE(payload.size) + payload
        } finally {
            name.fill(0)
            passBytes.fill(0)
            digest.fill(0)
            // JVM's transient immutable passString cannot be explicitly zeroed.
        }
    }

    /** Reports a generic boolean success without retaining greeting, IP, MD5
     * or any rejection text from the Soulseek server. The caller owns deadlines.
     */
    fun readResponse(input: InputStream): Boolean {
        val length = intLE(input)
        if (length !in 5..MAX_RESPONSE_BYTES) invalid()
        val message = ByteArrayInputStream(readExact(input, length))
        if (intLE(message) != LOGIN_CODE) invalid()
        when (message.read()) {
            0 -> {
                discardField(message)
                // Some servers append an invalid-username detail: ignore it.
                throw LoginDenied()
            }
            1 -> {
                discardField(message) // remote greeting: not logged or returned
                readExact(message, 4) // public IP: discarded
                discardField(message) // password hash echo: discarded
                val supporter = message.read()
                if (supporter != 0 && supporter != 1 || message.available() != 0) invalid()
                return true
            }
            else -> invalid()
        }
    }

    private fun field(bytes: ByteArray): ByteArray = intLE(bytes.size) + bytes
    private fun intLE(number: Int) = ByteArray(4) {
        (number ushr (8 * it)).toByte()
    }
    private fun intLE(input: InputStream): Int {
        val bytes = readExact(input, 4)
        return (bytes[0].toInt() and 255) or
            ((bytes[1].toInt() and 255) shl 8) or
            ((bytes[2].toInt() and 255) shl 16) or
            ((bytes[3].toInt() and 255) shl 24)
    }
    private fun discardField(input: ByteArrayInputStream) {
        val length = intLE(input)
        if (length < 0 || length > MAX_RESPONSE_BYTES ||
            length > input.available()) invalid()
        // Skip without materializing remote text as a String.
        if (input.skip(length.toLong()) != length.toLong()) invalid()
    }
    private fun readExact(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(bytes, offset, count - offset)
            if (n <= 0) throw EOFException("Incomplete A0 login response")
            offset += n
        }
        return bytes
    }
    private fun invalid(): Nothing =
        throw IOException("Malformed A0 experimental login response")
}
