package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A0 LOCALHOST-ONLY proof linking a P negotiation to its subsequent F byte socket.
 *
 * This is not a downloader or a Soulseek peer: both listeners must be local test
 * fixtures. There are no external host arguments, discovery, retries, background
 * lifecycle, Catalog bindings or secrets. A peer token is not authentication.
 * Only independently trusted test WAV hashes may be provided.
 */
internal object A0LocalTwoChannelSession {
    private const val TIMEOUT_MS = 4000

    sealed class Outcome {
        data class Completed(val receipt: A0VerifiedByteStager.Receipt) : Outcome()
        object Denied : Outcome()
    }

    fun receiveOwnedFixture(
        messagePort: Int,
        filePort: Int,
        username: String,
        messageConnectionToken: Int,
        fileConnectionToken: Int,
        transferToken: Int,
        remoteFilename: String,
        expectedBytes: Long,
        trustedSha256Hex: String,
        appPrivateDirectory: File
    ): Outcome {
        require(messagePort in 1..65535 && filePort in 1..65535 && messagePort != filePort) {
            "A0 requires two distinct loopback TCP ports"
        }
        require(fileConnectionToken > 0) { "A0 requires a positive file connection token" }
        require(trustedSha256Hex.length == 64 && trustedSha256Hex.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }) { "A0 needs an independently trusted test SHA-256" }
        require(appPrivateDirectory.isDirectory) { "A0 needs a pre-existing private test directory" }

        // Validate all peer wire inputs before connecting, to avoid opening
        // a message socket for an invalid request.
        val init = A0SoulseekPeerWireProbe.peerInit(username, messageConnectionToken)
        val request = A0SoulseekPeerWireProbe.downloadRequest(
            remoteFilename, transferToken, expectedBytes
        )

        val decision = Socket().use { messageSocket ->
            // Byte-literal loopback: no user-supplied IP, DNS or remote connections.
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            messageSocket.connect(InetSocketAddress(loopback, messagePort), TIMEOUT_MS)
            messageSocket.soTimeout = TIMEOUT_MS
            messageSocket.getOutputStream().apply {
                write(init)
                write(request)
                flush()
            }
            A0SoulseekPeerWireProbe.readTransferDecision(
                messageSocket.getInputStream(),
                expectedToken = transferToken,
                advertisedByteCount = expectedBytes
            )
        }

        if (!decision.allowed) return Outcome.Denied
        // An allowed-but-unsized response is ambiguous. Do not infer its size
        // from a search result and do not open the F channel.
        if (decision.peerReportedByteCount != expectedBytes) {
            throw IOException("A0 file transfer rejected: peer size not confirmed")
        }

        return Outcome.Completed(
            A0SoulseekLocalFileChannelProbe.receiveFixture(
                port = filePort,
                username = username,
                connectionToken = fileConnectionToken,
                transferToken = transferToken,
                expectedBytes = expectedBytes,
                trustedSha256Hex = trustedSha256Hex,
                appPrivateDirectory = appPrivateDirectory
            )
        )
    }
}
