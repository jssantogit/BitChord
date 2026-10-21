package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.IOException
import java.net.Socket

/**
 * A0 in-memory bridge from ONE parsed Soulseek SearchResponse to ONE bounded
 * licensed-file P/F attempt on the SAME previously authenticated server socket.
 *
 * IMPORTANT:
 * - A search response is untrusted; peer usernames are self-asserted over P.
 * - The server's GetPeerAddress response remains an unencrypted claim, NOT
 *   cryptographic peer authentication.
 * - "READERS STOPPED" requires the caller to have actually joined all search
 *   reader threads; the bridge cannot stop an external reader for the caller.
 * - Broad user-entered search terms never authorize downloads. Only exact,
 *   independently reviewed source filename + byte length + SHA can pass.
 * - Never log/serialize this object: it holds untrusted usernames/remote paths
 *   only in private RAM, not in any diagnostics or exported data.
 * - The class does not start searches, persist results, run in background or
 *   connect to an Android UI. It has no production downloads entrypoint.
 */
internal class A0SessionBoundSearchTransfer(
    private val authenticatedServer: Socket,
    private val localUsername: String,
    private val searchToken: Int,
    private val reference: A0OpenAudioCandidateGate.ReviewedReference,
    allowLoopbackForLocalTest: Boolean = false
) {
    enum class Selection { NONE, READY, REJECTED }

    private data class Selected(
        val peerUsername: String,
        val listing: A0OpenAudioCandidateGate.PublicPeerListing
    )
    private val transport=A0ServerAssociatedTransfer(allowLoopbackForLocalTest)
    private var candidate: Selected?=null
    private var poisoned=false
    private var readersStopped=false
    private var spent=false
    private var cancelled=false

    init { require(searchToken > 0) { "A0 needs a positive original search token" } }

    /**
     * Supply a COMPLETE framed peer code-9 response read from a P channel
     * after an actual expected search, plus its claimed PeerInit username or
     * server-indicated indirect P username.
     *
     * We never return remote paths, identities or file lists to the UI.
     * A single matching, unlocked, available, exact-size file is required.
     */
    @Synchronized
    fun offerSearchResult(frame: ByteArray, claimedPeerUsername: String): Selection {
        if (spent || readersStopped || cancelled || poisoned) return Selection.REJECTED
        val parsed=try {
            A0SoulseekSearchResponseParser.parse(frame,searchToken)
        } catch (_:IOException) {
            return Selection.REJECTED
        } catch (_:IllegalArgumentException) {
            return Selection.REJECTED
        }
        if (parsed.peerUsername != claimedPeerUsername) return Selection.REJECTED
        // The subsequent server lookup must be possible for the *same* peer;
        // enforce its username protocol before allowing network activity.
        try {
            A0PeerRendezvousWire.getPeerAddress(parsed.peerUsername)
        } catch (_:IllegalArgumentException) {
            return Selection.REJECTED
        }
        if (parsed.lockedFiles.any { it.remoteFilename == reference.expectedFilename }) {
            // A locked matching basename can never replace the public one.
            return Selection.REJECTED
        }
        val matches=parsed.files.map { file ->
            A0OpenAudioCandidateGate.PublicPeerListing(
                remoteFilename=file.remoteFilename,
                reportedSize=file.sizeBytes,
                locked=false,
                freeSlot=parsed.hasFreeUploadSlot
            )
        }.filter { listing ->
            A0OpenAudioCandidateGate.evaluate(reference,listing) is
                A0OpenAudioCandidateGate.Decision.EligibleForOneProbe
        }
        if (matches.size != 1) return Selection.REJECTED
        val selected=Selected(parsed.peerUsername,matches.single())
        val previous=candidate
        if (previous != null && previous != selected) {
            // Never silently switch peer/path after the first accepted reply.
            candidate=null
            poisoned=true
            return Selection.REJECTED
        }
        candidate=selected
        return Selection.READY
    }

    @Synchronized
    fun selectionStatus(): Selection = if (candidate!=null && !poisoned && !cancelled) {
        Selection.READY
    } else Selection.NONE

    /** Must only be called after BOTH search readers are joined/closed. */
    @Synchronized
    fun markReadersStopped() {
        check(!spent) { "A0 transfer already consumed" }
        readersStopped=true
    }

    /** Only one explicit caller invocation may hand the shared server socket to P/F. */
    fun transferOnce(privateDirectory: File): A0VerifiedByteStager.Receipt {
        val selected: Selected
        synchronized(this) {
            check(!spent) { "A0 P/F handoff already consumed" }
            if (cancelled) throw IOException("A0 search handoff cancelled")
            check(readersStopped) { "A0 search readers still own the server socket" }
            selected=checkNotNull(candidate) { "No authorized search candidate" }
            spent=true
            candidate=null
        }
        return transport.runOnce(
            authenticatedServer=authenticatedServer,
            localUsername=localUsername,
            peerUsername=selected.peerUsername,
            reference=reference,
            listing=selected.listing,
            privateDirectory=privateDirectory
        )
    }

    @Synchronized
    fun cancel() {
        cancelled=true
        candidate=null
        transport.cancel()
    }

    override fun toString(): String =
        "A0SessionBoundSearchTransfer(state=redacted)"
}
