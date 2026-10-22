package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A0-only stream state machine for one independently reviewed, licensed audio file.
 *
 * IMPORTANT: caller must separately verify remote P/F channel peer association
 * through an authenticated Soulseek server session. This object DOES NOT accept
 * IPs, open sockets, discover peers, resolve licensing or provide a live download
 * entrypoint. In particular, broad "naruto" search results are not authorization.
 *
 * The selected remote path is kept only in memory for the P request. It is never
 * used as a local path, logged, or included in the sanitized successful result.
 */
internal object A0LicensedOneFileSession {
    sealed class Plan {
        data class Ready(val session: Session) : Plan()
        data class Blocked(val reason: A0OpenAudioCandidateGate.Rejection) : Plan()
    }

    fun plan(
        independentlyReviewed: A0OpenAudioCandidateGate.ReviewedReference,
        untrustedListing: A0OpenAudioCandidateGate.PublicPeerListing
    ): Plan {
        return when (val gate = A0OpenAudioCandidateGate.evaluate(independentlyReviewed, untrustedListing)) {
            is A0OpenAudioCandidateGate.Decision.Rejected -> Plan.Blocked(gate.reason)
            is A0OpenAudioCandidateGate.Decision.EligibleForOneProbe -> Plan.Ready(
                Session(
                    untrustedListing.remoteFilename,
                    gate.expectedSize,
                    gate.expectedSha256
                )
            )
        }
    }

    /**
     * Explicit single-file transport probe. Trusts ONLY that the user selected
     * one bounded public listing; DOES NOT claim independent source integrity.
     */
    fun observedSession(listing: A0OpenAudioCandidateGate.PublicPeerListing): Session {
        require(!listing.locked && listing.freeSlot &&
            listing.reportedSize in 44L..16L*1024L*1024L &&
            A0ModernIncomingTransfer.isSafeRemoteName(listing.remoteFilename)
        ) { "A0 observed listing is unsafe or unavailable" }
        return Session(listing.remoteFilename,listing.reportedSize,null)
    }

    class Session internal constructor(
        private val reviewedRemotePath: String,
        private val expectedBytes: Long,
        private val independentlyTrustedHash: String?,
    ) {
        private enum class Stage { READY, QUEUED, UPLOAD_ACCEPTED, CONSUMED }
        private var stage = Stage.READY
        private var ticket: A0ModernIncomingTransfer.Ticket? = null

        /** Peer P channel must already have been associated with a server-verified peer. */
        @Synchronized
        fun queueUpload(outgoingP: OutputStream) {
            check(stage == Stage.READY) { "A0 one-file probe already used" }
            stage = Stage.CONSUMED // fail closed if output fails partway
            val wire = A0SoulseekPeerWireProbe.queueUploadRequest(reviewedRemotePath)
            outgoingP.write(wire)
            outgoingP.flush()
            stage = Stage.QUEUED
        }

        /** Accept only a matching uploader-initiated P40 message, one time. */
        @Synchronized
        fun acceptUpload(incomingP: InputStream, outgoingP: OutputStream) {
            check(stage == Stage.QUEUED) { "A0 QueueUpload handshake not completed" }
            stage = Stage.CONSUMED // do not retry after malformed request or I/O failure
            val trusted=independentlyTrustedHash
            ticket = if(trusted==null) A0ModernIncomingTransfer.acceptObservedUpload(
                incomingP,outgoingP,reviewedRemotePath,expectedBytes
            ) else A0ModernIncomingTransfer.acceptUpload(
                incomingP,outgoingP,reviewedRemotePath,expectedBytes,trusted
            )
            stage = Stage.UPLOAD_ACCEPTED
        }

        /** Only after peer identity + P40/P41, and a matched incoming F channel. */
        @Synchronized
        fun receiveFile(
            incomingF: InputStream,
            outgoingF: OutputStream,
            appPrivateDirectory: File,
            isCancelled: () -> Boolean = { false }
        ): A0VerifiedByteStager.Receipt {
            check(stage == Stage.UPLOAD_ACCEPTED) { "A0 upload ticket not accepted" }
            stage = Stage.CONSUMED // even timeout/hash mismatch blocks replay
            val accepted = checkNotNull(ticket)
            ticket = null
            return A0ModernIncomingTransfer.receiveAfterFConnection(
                incomingF, outgoingF, accepted, appPrivateDirectory, isCancelled
            )
        }
    }
}
