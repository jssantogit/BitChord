package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress

/**
 * Wire-level A0 peer discovery/request codec.
 *
 * Based on Nicotine+ Soulseek messages:
 *  server 3 GetPeerAddress(username) -> username, reversed IPv4, uint32 port,
 *       uint32 obfuscation type, uint16 obfuscated port;
 *  direct P PeerInit: size, code 1 (byte), own user, "P", uint32 zero;
 *  fallback server 18 ConnectToPeer: token, target username, "P";
 *  inbound indirect PierceFirewall: size=5, code 0 (byte), request token;
 *  server 1001 CantConnectToPeer: request token.
 *
 * Server responses carry a claimed username but are not cryptographic
 * authentication. Caller owns the server stream; do not create concurrent
 * readers. Never log usernames, IPs, raw frames, tokens, files or credentials.
 */
internal object A0PeerRendezvousWire {
    private const val MAX_SERVER_FRAME = 1_048_576
    private const val MAX_USERNAME_BYTES = 30

    /** No untrusted peer or session details are retained in this exception. */
    class SessionReplaced : IOException("A0 server session replaced")

    sealed class ServerEvent {
        data class Address(val address: InetAddress, val port: Int, val obfuscated: Boolean) : ServerEvent()
        object UnsupportedAddress : ServerEvent()
        object PeerUnreachable : ServerEvent()
        object Unrelated : ServerEvent()
    }

    fun getPeerAddress(username: String): ByteArray =
        serverFrame(3, usernameBytes(username))

    fun connectToPeer(token: Int, username: String): ByteArray {
        require(token > 0) { "A0 indirect request requires a nonzero correlation token" }
        return serverFrame(18, le32Bytes(token) + usernameBytes(username) + string("P"))
    }

    fun directPeerInit(localUsername: String): ByteArray {
        val content = byteArrayOf(1) + usernameBytes(localUsername) + string("P") + le32Bytes(0)
        return le32Bytes(content.size) + content
    }

    fun readServerEvent(input: InputStream, expectedPeer: String, pendingToken: Int? = null): ServerEvent {
        validateUsername(expectedPeer)
        require(pendingToken == null || pendingToken > 0)
        val size = le32(input)
        if (size !in 4..MAX_SERVER_FRAME) malformed()
        val type = le32(input)
        val body = ByteArrayInputStream(exact(input, size-4))
        return when (type) {
            3 -> {
                val peer = readUsername(body)
                val reversedAddress = exact(body,4).reversedArray()
                val port = le32(body)
                val obfuscation = le32(body)
                val obfuscatedPort = le16(body)
                if (body.available() != 0) malformed()
                if (peer != expectedPeer) ServerEvent.Unrelated
                // Framing and endpoint policy are deliberately separate:
                // only the transport caller may allow loopback for JVM tests.
                // Public runtime rejects all private/local/reserved endpoints
                // via A0ServerAssociatedTransfer.requireEndpoint().
                else if (obfuscation != 0 || obfuscatedPort != 0 ||
                    port !in 1..65535 || reversedAddress.all { it.toInt() == 0 }) {
                    ServerEvent.UnsupportedAddress
                } else ServerEvent.Address(InetAddress.getByAddress(reversedAddress), port, false)
            }
            1001 -> {
                val token = le32(body)
                // Some peers/server versions may append optional username data.
                if (pendingToken != null && token == pendingToken) ServerEvent.PeerUnreachable
                else ServerEvent.Unrelated
            }
            41 -> throw SessionReplaced()
            else -> ServerEvent.Unrelated
        }
    }

    fun readIndirectPierce(input: InputStream, expectedToken: Int): Boolean {
        require(expectedToken > 0)
        if (le32(input) != 5) malformed()
        if (exact(input,1)[0].toInt() != 0) malformed()
        if (le32(input) != expectedToken) malformed()
        return true
    }

    fun isRoutableIpv4(ip: ByteArray): Boolean {
        if (ip.size != 4) return false
        val a = ip[0].toInt() and 255
        val b = ip[1].toInt() and 255
        val c = ip[2].toInt() and 255
        return a in 1..223 && a != 10 && a != 127 &&
            !(a == 100 && b in 64..127) &&
            !(a == 169 && b == 254) &&
            !(a == 172 && b in 16..31) &&
            !(a == 192 && (b == 0 || b == 168)) &&
            !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) &&
            !(a == 203 && b == 0 && c == 113) && a != 0 &&
            !(a == 192 && b == 0 && c == 2)
    }

    private fun usernameBytes(name: String): ByteArray {
        validateUsername(name)
        return string(name)
    }
    private fun validateUsername(name: String) {
        require(name.isNotEmpty() && name.length <= MAX_USERNAME_BYTES &&
            name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' ||
                it == '_' || it == '-' }) {
            "A0 server peer name is empty or unsupported"
        }
    }
    private fun readUsername(input: ByteArrayInputStream): String {
        val n = le32(input)
        if (n !in 1..MAX_USERNAME_BYTES || n > input.available()) malformed()
        val b=exact(input,n)
        val name=String(b,Charsets.US_ASCII)
        if (name.length != n) malformed()
        try { validateUsername(name) } catch (_:IllegalArgumentException) { malformed() }
        return name
    }
    private fun string(s: String): ByteArray {
        val b=s.toByteArray(Charsets.US_ASCII)
        return le32Bytes(b.size)+b
    }
    private fun serverFrame(code: Int, payload: ByteArray): ByteArray {
        val body=le32Bytes(code)+payload
        return le32Bytes(body.size)+body
    }
    private fun le32Bytes(n: Int): ByteArray = ByteArray(4){(n ushr (8*it)).toByte()}
    private fun le32(input: InputStream): Int {
        val b=exact(input,4)
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun le16(input: InputStream): Int {
        val b=exact(input,2)
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8)
    }
    private fun exact(input: InputStream,count: Int): ByteArray {
        val result=ByteArray(count)
        var index=0
        while(index<count) {
            val n=input.read(result,index,count-index)
            if(n<=0)throw EOFException("A0 peer rendezvous protocol frame incomplete")
            index+=n
        }
        return result
    }
    private fun malformed(): Nothing =
        throw IOException("A0 invalid or unsupported peer rendezvous frame")
}
