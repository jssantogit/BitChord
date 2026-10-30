package com.music.bitchord.acquisition.a0

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real one-shot authenticated Soulseek transport for the FUTURE opt-in worker.
 *
 * A NEW server login and A0ServerAssociatedTransfer is created PER PEER.
 * Search readers from other authenticated sessions are never reused.
 *
 * OFF/UNREACHABLE from UI and DownloadService: this is a devDebug-only
 * implementation of A0AndroidAutoAcquisitionPort.AuthenticatedTransport.
 * Only the future worker can supply a vetted Choice after user opt-in.
 * The protocol is plaintext TCP: the user must have saved a dedicated account.
 */
internal class A0FreshAuthenticatedAutoTransport(
    context:Context,
    private val expectedGeneration:Long,
):A0AndroidAutoAcquisitionPort.AuthenticatedTransport {
    private val app=context.applicationContext
    private val cancelled=AtomicBoolean(false)
    @Volatile private var socket:Socket?=null
    @Volatile private var transfer:A0ServerAssociatedTransfer?=null

    override fun receive(
        candidate:A0ObservedCandidateHandoff.Choice,
        scratchDirectory:File,
        isCancelled:()->Boolean,
    ):A0AutoAcquisitionExecutor.FetchResult {
        if(com.music.bitchord.BuildConfig.FLAVOR!="dev" || !com.music.bitchord.BuildConfig.DEBUG)
            throw IOException("Soulseek unattended transport is disabled")
        if(!A0AutoTransportAdmission.accepts(candidate) ||
            cancelled.get() || isCancelled() ||
            !SoulseekActiveSession.isCurrent(expectedGeneration))
            throw IOException("Soulseek auto transport preflight rejected")

        var session:A0AutoAuthenticatedServer.Session?=null
        var current:A0ServerAssociatedTransfer?=null
        try {
            session=A0AutoAuthenticatedServer.open(
                app,expectedGeneration,
                isCancelled={ cancelled.get() || isCancelled() },
                onSocketReady={socket=it},
            )
            val connection=session.socket
            val username=session.username
            current=A0ServerAssociatedTransfer()
            if(!SoulseekActiveSession.registerAutoTransfer(current,expectedGeneration))
                throw IOException("Soulseek account revoked before P/F")
            transfer=current
            val listing=A0OpenAudioCandidateGate.PublicPeerListing(
                remoteFilename=candidate.remotePath,
                reportedSize=candidate.sizeBytes,
                locked=false,freeSlot=true,
            )
            val receipt=current.runObservedOnce(
                authenticatedServer=connection,
                localUsername=username,
                peerUsername=candidate.peerUsername,
                listing=listing,
                privateDirectory=scratchDirectory,
                externalCancelled={
                    cancelled.get() || isCancelled() ||
                        !SoulseekActiveSession.isCurrent(expectedGeneration)
                }
            )
            if(cancelled.get() || isCancelled() ||
                !SoulseekActiveSession.isCurrent(expectedGeneration)) {
                runCatching {receipt.file.delete()}
                throw IOException("Soulseek auto transfer revoked after receiving bytes")
            }
            return A0AutoAcquisitionExecutor.FetchResult.Received(receipt)
        }catch(error:A0ServerAssociatedTransfer.PeerAddressFailure) {
            if(error.kind==A0ServerAssociatedTransfer.AddressFailureKind.PEER_UNREACHABLE &&
                !cancelled.get() && !isCancelled() &&
                SoulseekActiveSession.isCurrent(expectedGeneration))
                return A0AutoAcquisitionExecutor.FetchResult.RetryableConnectionFailure
            throw IOException("Soulseek auto peer lookup failed",error)
        }finally {
            current?.let {
                SoulseekActiveSession.detachAutoTransfer(it)
                runCatching {it.cancel()}
            }
            transfer=null
            session?.close()
            socket=null
        }
    }

    override fun cancel() {
        cancelled.set(true)
        runCatching {transfer?.cancel()}
        runCatching {socket?.close()}
    }

}

/**
 * Last defensive check before handling saved plaintext TCP credentials.
 * Selection scoring happens separately per target, never inside this gate.
 */
internal object A0AutoTransportAdmission {
    fun accepts(candidate:A0ObservedCandidateHandoff.Choice):Boolean {
        if(candidate.peerAvailability.tier!=A0PeerAvailability.Tier.READY ||
            candidate.sizeBytes !in A0ObservedTransferLimits.MIN_AUDIO_BYTES..
                A0ObservedTransferLimits.MAX_AUDIO_BYTES ||
            candidate.claim.format.lowercase(Locale.ROOT) !in
                setOf("mp3","flac","wav","aac","opus","ogg") ||
            !A0ModernIncomingTransfer.isSafeRemoteName(candidate.remotePath))
            return false
        return runCatching {
            A0PeerRendezvousWire.getPeerAddress(candidate.peerUsername)
            true
        }.getOrDefault(false)
    }
}
