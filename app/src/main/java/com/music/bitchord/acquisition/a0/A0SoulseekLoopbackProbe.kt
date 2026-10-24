package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * A0 transport-level feasibility probe. This is NOT a usable Soulseek client.
 *
 * Hard restricted to loopback so that builds/tests cannot accidentally send cleartext
 * Soulseek credentials or queries to a real server. Contains no UI or production entrypoint.
 * Validates only the server login/search framing from the upstream protocol reference:
 * https://github.com/jpdillingham/Soulseek.NET/tree/master/src/Messaging/Messages/Server
 *
 * It does NOT interpret search results, negotiate with peers, or transfer files.
 * Do not ship a production engine based on this fixture.
 */
internal class A0SoulseekLoopbackProbe : Closeable {
    class LoginRejectedException : IOException("Probe login rejected")

    private var socket: Socket? = null

    var isAuthenticated: Boolean = false
        private set

    /**
     * Explicit start only. This blocking operation must run off Android's main thread.
     * Client minor version here is only a local test vector; no real identity is registered.
     */
    fun connect(host: String, port: Int, username: String, password: String, minorVersion: Int) {
        require(host == "127.0.0.1" || host == "::1" || host == "localhost") {
            "A0 probe is restricted to loopback"
        }
        require(port in 1..65535)
        require(username.isNotBlank() && username.toByteArray(Charsets.UTF_8).size <= 256)
        require(password.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size <= 256)
        require(minorVersion > 100) // Never impersonate reserved upstream client IDs.
        check(socket == null) { "Probe session already started; create a new instance" }

        val connection = Socket()
        socket = connection
        try {
            connection.connect(InetSocketAddress(InetAddress.getByName(host), port), SOCKET_TIMEOUT_MS)
            connection.soTimeout = SOCKET_TIMEOUT_MS

            val hash = md5Hex(username + password)
            val login = ByteArrayOutputStream().apply {
                write(intLE(SERVER_LOGIN))
                write(textField(username))
                write(textField(password))
                write(intLE(PROTOCOL_MAJOR_VERSION))
                write(textField(hash))
                write(intLE(minorVersion))
            }.toByteArray()
            writeFrame(connection, login)

            val response = ByteArrayInputStream(readFrame(connection.getInputStream()))
            if (readIntLE(response) != SERVER_LOGIN) throw IOException("Unexpected probe login response")
            val accepted = response.read()
            if (accepted != 0 && accepted != 1) throw IOException("Malformed probe login status")
            // Intentionally discard server text; it may contain personally identifying data.
            readTextField(response)
            if (accepted == 0) throw LoginRejectedException()
            readFully(response, 4) // Public IP echoed by server, never recorded.
            readTextField(response) // MD5 echoed by server, never recorded.
            if (response.read() !in 0..1) throw IOException("Malformed supporter flag")
            isAuthenticated = true
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    /** Sends a search frame; there is no result/candidate parser in this A0 probe. */
    fun search(query: String, token: Int) {
        require(token > 0) { "Token must be positive" }
        val queryBytes = query.toByteArray(Charsets.UTF_8)
        require(query.isNotBlank() && queryBytes.size <= 512 && !query.contains('\u0000'))
        check(isAuthenticated) { "Probe must authenticate first" }
        val connection = socket ?: throw IllegalStateException("Probe is closed")
        try {
            val request = ByteArrayOutputStream().apply {
                write(intLE(SERVER_FILE_SEARCH))
                write(intLE(token))
                write(intLE(queryBytes.size))
                write(queryBytes)
            }.toByteArray()
            writeFrame(connection, request)
        } catch (failure: IOException) {
            close()
            throw failure
        }
    }

    override fun close() {
        isAuthenticated = false
        val old = socket
        socket = null
        try {
            old?.close()
        } catch (_: IOException) {
            // The connection is already unusable; do not expose sensitive network details.
        }
    }

    private fun writeFrame(socket: Socket, content: ByteArray) {
        if (content.size !in 4..MAX_FRAME_BYTES) throw IOException("Invalid outgoing frame")
        val output = socket.getOutputStream()
        output.write(intLE(content.size))
        output.write(content)
        output.flush()
    }

    private fun readFrame(input: InputStream): ByteArray {
        val length = readIntLE(input)
        if (length !in 4..MAX_FRAME_BYTES) throw IOException("Invalid incoming frame length")
        return readFully(input, length)
    }

    private fun readTextField(input: ByteArrayInputStream): String {
        val length = readIntLE(input)
        if (length < 0 || length > 65_536 || length > input.available()) {
            throw IOException("Invalid protocol string length")
        }
        return String(readFully(input, length), Charsets.UTF_8)
    }

    private fun textField(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return intLE(bytes.size) + bytes
    }

    private fun readIntLE(input: InputStream): Int {
        val field = readFully(input, 4)
        return (field[0].toInt() and 255) or ((field[1].toInt() and 255) shl 8) or
            ((field[2].toInt() and 255) shl 16) or ((field[3].toInt() and 255) shl 24)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(bytes, read, count - read)
            if (n < 0) throw IOException("Incomplete protocol frame")
            if (n == 0) continue
            read += n
        }
        return bytes
    }

    private fun intLE(value: Int): ByteArray = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte()
    )

    private fun md5Hex(value: String): String {
        // Soulseek's historical login wire protocol uses MD5; this is not a
        // secure authentication scheme and MUST NOT be used for password storage.
        val digest = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(Character.forDigit((b.toInt() ushr 4) and 15, 16))
            hex.append(Character.forDigit(b.toInt() and 15, 16))
        }
        return hex.toString()
    }

    private companion object {
        const val SERVER_LOGIN = 1
        const val SERVER_FILE_SEARCH = 26
        const val PROTOCOL_MAJOR_VERSION = 170
        const val MAX_FRAME_BYTES = 1_048_576
        const val SOCKET_TIMEOUT_MS = 4_000
    }
}
