package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Isolated A0 experiment: routes one reviewed file through two real TCP
 * channels, bound to an offer previously received on the authenticated server
 * connection. No arbitrary target addresses, DNS, auto-retry or background job.
 *
 * This is NOT yet wired to the Android UI, a live SearchResponse candidate, or
 * the Soulseek server's GetPeerAddress/ConnectToPeer request flow.
 *
 * Bound means the indicated server P endpoint and remote F IP plus the P40/F
 * transfer token must match. This is NOT cryptographic authentication: the
 * legacy Soulseek server and peer protocol are unencrypted. It is an essential
 * narrow association check before any future real network/owned-peer pilot.
 *
 * Accepts ONLY independently licensed+SHA-verified candidate metadata. Never
 * download arbitrary broad-search results or import into the media library.
 */
internal class A0ServerOfferBoundPFProbe {
    private val used = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var pSocket: Socket? = null
    @Volatile private var fSocket: Socket? = null
    @Volatile private var fListener: ServerSocket? = null

    fun cancel() {
        cancelled.set(true)
        runCatching { pSocket?.close() }
        runCatching { fSocket?.close() }
        runCatching { fListener?.close() }
    }

    /**
     * @param offer Only use an Offer parsed from an existing real Soulseek
     *        server code-18 message. Tokens are correlation, NOT credentials.
     * @param onListenerReady Allows caller to announce the F listener port on
     *        the existing server session before P43 requests data. Must not
     *        accept or use an unverified remote connection.
     */
    fun runOnce(
        offer: A0IndirectSearchWire.Offer,
        reference: A0OpenAudioCandidateGate.ReviewedReference,
        listing: A0OpenAudioCandidateGate.PublicPeerListing,
        privateDirectory: File,
        onListenerReady: (Int) -> Unit = {}
    ): A0VerifiedByteStager.Receipt {
        check(used.compareAndSet(false, true)) { "A0 P/F probe can only run once" }
        checkNotCancelled()
        require(offer.token > 0 && offer.port in 1..65535 &&
            offer.username.isNotBlank()) { "A0 missing server-associated peer" }
        // No socket may exist until this independently reviewed candidate passes.
        val plan = A0LicensedOneFileSession.plan(reference, listing)
        require(plan is A0LicensedOneFileSession.Plan.Ready) {
            "A0 peer listing does not match independently reviewed original"
        }
        require(privateDirectory.isDirectory) { "A0 requires private test directory" }
        val session = (plan as A0LicensedOneFileSession.Plan.Ready).session
        try {
            ServerSocket(0, 1).use { listener ->
                fListener = listener
                listener.soTimeout = 4000
                checkNotCancelled()
                onListenerReady(listener.localPort)
                checkNotCancelled()
                Socket().use { p ->
                    pSocket = p
                    p.connect(InetSocketAddress(offer.address, offer.port), 3000)
                    p.soTimeout = 4000
                    checkNotCancelled()
                    // Confirm the server-supplied P negotiation token on this
                    // socket before sending any file request.
                    p.getOutputStream().apply {
                        write(A0IndirectSearchWire.pierceFirewall(offer.token))
                        flush()
                    }
                    session.queueUpload(p.getOutputStream())
                    checkNotCancelled()
                    session.acceptUpload(p.getInputStream(), p.getOutputStream())
                    checkNotCancelled()
                    listener.accept().use { f ->
                        fSocket = f
                        f.soTimeout = 4000
                        requireSamePeerAddress(offer.address, f.inetAddress)
                        checkNotCancelled()
                        val result = session.receiveFile(
                            f.getInputStream(),
                            f.getOutputStream(),
                            privateDirectory,
                            isCancelled = { cancelled.get() || Thread.currentThread().isInterrupted }
                        )
                        // No verified artifact escapes if cancellation races with EOF.
                        if (cancelled.get() || Thread.currentThread().isInterrupted) {
                            result.file.delete()
                            throw IOException("A0 transfer cancelled")
                        }
                        return result
                    }
                }
            }
        } finally {
            fSocket = null
            pSocket = null
            fListener = null
        }
    }

    private fun checkNotCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw IOException("A0 transfer cancelled")
        }
    }

    companion object {
        fun requireSamePeerAddress(
            serverIndicatedAddress: InetAddress,
            incomingFileAddress: InetAddress
        ) {
            if (!serverIndicatedAddress.address.contentEquals(incomingFileAddress.address)) {
                throw IOException("A0 F endpoint differs from server P peer")
            }
        }
    }
}
