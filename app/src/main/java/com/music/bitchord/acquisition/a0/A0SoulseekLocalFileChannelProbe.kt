package com.music.bitchord.acquisition.a0

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A0 strictly-local file-channel protocol experiment.
 *
 * No configurable endpoint: ONLY literal IPv4 loopback, without DNS.
 * This class is not a Soulseek transfer engine; no real peer, remote files,
 * discovery, retries or production entrypoint are exposed.
 *
 * A direct Soulseek file channel begins with a length-prefixed PeerInit(type F),
 * then a raw little-endian 32-bit transfer token and unframed file bytes.
 * Cross-check: Soulseek.NET PeerConnectionManager.GetTransferConnectionAsync.
 *
 * trustedSha256Hex MUST come from an independent test-controlled file.
 * Real Soulseek search metadata does NOT authenticate remote bytes.
 */
internal object A0SoulseekLocalFileChannelProbe {
    private const val TIMEOUT_MS = 4000
    private const val MAX_USERNAME_BYTES = 128
    private const val MAX_LOCAL_TEST_BYTES = 512L * 1024L * 1024L

    fun receiveFixture(
        port: Int,
        username: String,
        connectionToken: Int,
        transferToken: Int,
        expectedBytes: Long,
        trustedSha256Hex: String,
        appPrivateDirectory: File
    ): A0VerifiedByteStager.Receipt {
        require(port in 1..65535) { "A0 requires a valid loopback port" }
        val name = username.toByteArray(Charsets.UTF_8)
        require(name.isNotEmpty() && name.size <= MAX_USERNAME_BYTES && !username.contains('\u0000')) {
            "A0 requires a valid local username"
        }
        require(connectionToken > 0 && transferToken > 0) {
            "A0 correlation tokens must be positive"
        }
        require(expectedBytes in 44L..MAX_LOCAL_TEST_BYTES) {
            "A0 requires a bounded local fixture"
        }
        require(trustedSha256Hex.length == 64 && trustedSha256Hex.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }) { "A0 requires an independently trusted test digest" }
        require(appPrivateDirectory.isDirectory) { "A0 requires an existing test staging directory" }

        val initPayload = byteArrayOf(1) +
            intLE(name.size) + name +
            intLE(1) + byteArrayOf('F'.code.toByte()) +
            intLE(connectionToken)

        Socket().use { connection ->
            // Hardcode loopback bytes; caller cannot supply or resolve a remote hostname.
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            connection.connect(InetSocketAddress(loopback, port), TIMEOUT_MS)
            connection.soTimeout = TIMEOUT_MS

            val output = connection.getOutputStream()
            output.write(intLE(initPayload.size))
            output.write(initPayload)
            // Soulseek F-channel transfer token is raw: NO 4-byte frame length.
            output.write(intLE(transferToken))
            output.flush()

            return A0VerifiedByteStager.stage(
                source = connection.getInputStream(),
                privateDirectory = appPrivateDirectory,
                expectedByteCount = expectedBytes,
                expectedSha256Hex = trustedSha256Hex
            )
        }
    }

    private fun intLE(n: Int): ByteArray = ByteArray(4) { i ->
        (n ushr (8 * i)).toByte()
    }
}
