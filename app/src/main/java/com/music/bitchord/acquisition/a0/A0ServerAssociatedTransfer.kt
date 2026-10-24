package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A0-only single-file peer-initiated transport probe, behind strict source
 * rights, exact size and independently obtained SHA-256.
 *
 * Owns the server socket for this operation: NO other thread may read from it.
 * Caller must already have completed the Soulseek login successfully, chosen
 * exactly one peer, and bound that peer to a separately verified candidate.
 *
 * Requests GetPeerAddress, tries a direct P PeerInit, then on *TCP connect
 * failure only* requests indirect P via ConnectToPeer. Both P and F must
 * correlate to the server-indicated peer endpoint; P40 and F tokens must match.
 *
 * This is NOT cryptographic peer authentication; Soulseek transport is plain TCP.
 * It is not connected to the Android app UI, media library or arbitrary
 * artist/title search results. The default constructor rejects local/private
 * IP targets. Localhost is enabled solely by the explicit JVM test fixture.
 */
internal class A0ServerAssociatedTransfer(
    private val allowLoopbackForLocalTest: Boolean = false
) {
    enum class Phase {
        GATE_PASSED,
        PEER_ADDRESS_REQUESTED,
        PEER_ADDRESS_RESOLVED,
        P_DIRECT_ATTEMPT,
        P_INDIRECT_REQUESTED,
        P_ESTABLISHED,
        QUEUE_UPLOAD_SENT,
        TRANSFER_ACCEPTED,
        F_WAIT_STARTED,
        F_ESTABLISHED,
        BYTES_RECEIVED
    }

    private val used=AtomicBoolean(false)
    private val cancelled=AtomicBoolean(false)
    @Volatile private var activeServer:Socket?=null
    @Volatile private var activeP:Socket?=null
    @Volatile private var activeF:Socket?=null
    @Volatile private var activeListener:ServerSocket?=null

    fun cancel() {
        cancelled.set(true)
        runCatching{activeF?.close()}
        runCatching{activeP?.close()}
        runCatching{activeListener?.close()}
        // Explicitly owns the server socket for this single bounded operation.
        runCatching{activeServer?.close()}
    }

    fun runOnce(
        authenticatedServer: Socket,
        localUsername: String,
        peerUsername: String,
        reference: A0OpenAudioCandidateGate.ReviewedReference,
        listing: A0OpenAudioCandidateGate.PublicPeerListing,
        privateDirectory: File
    ): A0VerifiedByteStager.Receipt = runInternal(
        authenticatedServer,localUsername,peerUsername,reference,listing,privateDirectory,{})

    fun runObservedOnce(
        authenticatedServer: Socket,
        localUsername: String,
        peerUsername: String,
        listing: A0OpenAudioCandidateGate.PublicPeerListing,
        privateDirectory: File,
        onPhase:(Phase)->Unit={}
    ): A0VerifiedByteStager.Receipt = runInternal(
        authenticatedServer,localUsername,peerUsername,null,listing,privateDirectory,onPhase)

    private fun runInternal(
        authenticatedServer: Socket,
        localUsername: String,
        peerUsername: String,
        reference: A0OpenAudioCandidateGate.ReviewedReference?,
        listing: A0OpenAudioCandidateGate.PublicPeerListing,
        privateDirectory: File,
        onPhase:(Phase)->Unit
    ): A0VerifiedByteStager.Receipt {
        check(used.compareAndSet(false,true)){"A0 server peer transfer already attempted"}
        checkNotCancelled()
        // Rights and destination are checked before any request, socket or
        // possible link to a peer. Never treat search results as license.
        val session=if(reference==null)
            A0LicensedOneFileSession.observedSession(listing)
        else {
            val plan=A0LicensedOneFileSession.plan(reference,listing)
            require(plan is A0LicensedOneFileSession.Plan.Ready) {
                "A0 exact reviewed file gate rejected this peer listing"
            }
            plan.session
        }
        require(privateDirectory.isDirectory){"A0 needs a private test directory"}
        onPhase(Phase.GATE_PASSED)
        val peerInit=A0PeerRendezvousWire.directPeerInit(localUsername)
        val getAddress=A0PeerRendezvousWire.getPeerAddress(peerUsername)
        val deadline=A0TransferDeadlines.negotiate(System.nanoTime())

        activeServer=authenticatedServer
        try {
            ServerSocket(0,2).use{listener->
                activeListener=listener
                listener.soTimeout=4000
                checkNotCancelled()
                authenticatedServer.soTimeout=4000
                authenticatedServer.getOutputStream().apply{
                    write(A0OneShotPublicSearchWire.setWaitPort(listener.localPort))
                    write(getAddress)
                    flush()
                }
                onPhase(Phase.PEER_ADDRESS_REQUESTED)
                val resolved=resolvePeer(authenticatedServer,peerUsername,deadline)
                requireEndpoint(resolved.address,allowLoopbackForLocalTest)
                onPhase(Phase.PEER_ADDRESS_RESOLVED)
                val p = try {
                    checkNotCancelled()
                    onPhase(Phase.P_DIRECT_ATTEMPT)
                    Socket().also{socket->
                        activeP=socket
                        try {
                            socket.connect(InetSocketAddress(resolved.address,resolved.port),3000)
                            socket.soTimeout=4000
                            checkNotCancelled()
                            socket.getOutputStream().apply {write(peerInit);flush()}
                        } catch (error:IOException) {
                            socket.close()
                            throw error
                        }
                    }
                } catch (_:ConnectException) {
                    onPhase(Phase.P_INDIRECT_REQUESTED)
                    requestIndirect(authenticatedServer,listener,resolved.address,
                        peerUsername,deadline)
                } catch (_:SocketTimeoutException) {
                    onPhase(Phase.P_INDIRECT_REQUESTED)
                    requestIndirect(authenticatedServer,listener,resolved.address,
                        peerUsername,deadline)
                }
                p.use{peer->
                    activeP=peer
                    checkNotCancelled()
                    onPhase(Phase.P_ESTABLISHED)
                    session.queueUpload(peer.getOutputStream())
                    onPhase(Phase.QUEUE_UPLOAD_SENT)
                    session.acceptUpload(peer.getInputStream(),peer.getOutputStream())
                    onPhase(Phase.TRANSFER_ACCEPTED)
                    checkNotCancelled()
                    // A direct inbound F is not guaranteed behind carrier/
                    // residential NAT. Also accept a server-mediated code-18
                    // F offer from this exact peer; reply PierceFirewall on
                    // outbound F before reading FileTransferInit.
                    onPhase(Phase.F_WAIT_STARTED)
                    A0FileChannelRendezvous.awaitFileSocket(
                        server=authenticatedServer,
                        listener=listener,
                        selectedPeerUsername=peerUsername,
                        selectedPeerAddress=resolved.address,
                        absoluteDeadlineNanos=deadline,
                        allowLoopbackForLocalTest=allowLoopbackForLocalTest,
                        isCancelled={
                            cancelled.get() || Thread.currentThread().isInterrupted
                        },
                        onSocketActivated={activeF=it}
                    ).use{f->
                        activeF=f
                        onPhase(Phase.F_ESTABLISHED)
                        // Do not reuse the 15s P/F negotiation clock for bytes.
                        val receiveDeadline=A0TransferDeadlines.receive(System.nanoTime())
                        f.soTimeout=4000
                        if(!f.inetAddress.address.contentEquals(resolved.address.address)) {
                            throw IOException("A0 F peer does not match server-selected P address")
                        }
                        checkNotCancelled()
                        val receipt=session.receiveFile(
                            f.getInputStream(),f.getOutputStream(),privateDirectory,
                            isCancelled={cancelled.get()||Thread.currentThread().isInterrupted||
                                A0TransferDeadlines.isExpired(
                                    receiveDeadline,System.nanoTime())}
                        )
                        onPhase(Phase.BYTES_RECEIVED)
                        if(cancelled.get()||Thread.currentThread().isInterrupted||
                            A0TransferDeadlines.isExpired(
                                receiveDeadline,System.nanoTime())) {
                            receipt.file.delete()
                            throw IOException("A0 transfer cancelled or expired")
                        }
                        return receipt
                    }
                }
            }
        } finally {
            activeF=null
            activeP=null
            activeListener=null
            activeServer=null
        }
    }

    /** Only fixed allowlisted reasons and numeric backlog counts are exposed to diagnostics. */
    enum class AddressFailureKind {
        BACKLOG_LIMIT, SESSION_REPLACED, UNSUPPORTED_ADDRESS, PEER_UNREACHABLE,
        END_OF_STREAM, TIMEOUT, SOCKET, PROTOCOL_OR_IO
    }

    class PeerAddressFailure(
        val kind:AddressFailureKind,
        val unrelatedFrames:Int,
        cause:Throwable?=null
    ): IOException("A0 peer address resolution failed: ${kind.name}",cause)

    private fun resolvePeer(server:Socket,peer:String,deadline:Long):
        A0PeerRendezvousWire.ServerEvent.Address {
        // A user can spend seconds choosing a file while search-related
        // code-18 peer offers accumulate on this authenticated connection.
        // Twelve unrelated frames are NOT proof of a network failure.
        // Bound both the number of drained frames and the existing 15s deadline.
        val maxUnrelatedFrames=256
        var unrelatedFrames=0
        while(unrelatedFrames < maxUnrelatedFrames) {
            checkNotCancelled()
            val event=try {
                server.soTimeout=remainingTimeout(deadline)
                // No ConnectToPeer has been issued for this transfer yet.
                // Never treat an unrelated code-1001 token as our failure.
                A0PeerRendezvousWire.readServerEvent(
                    server.getInputStream(),peer,pendingToken=null
                )
            }catch(error:A0PeerRendezvousWire.SessionReplaced) {
                throw PeerAddressFailure(AddressFailureKind.SESSION_REPLACED,
                    unrelatedFrames,error)
            }catch(error:java.io.EOFException) {
                throw PeerAddressFailure(AddressFailureKind.END_OF_STREAM,
                    unrelatedFrames,error)
            }catch(error:SocketTimeoutException) {
                throw PeerAddressFailure(AddressFailureKind.TIMEOUT,
                    unrelatedFrames,error)
            }catch(error:java.net.SocketException) {
                throw PeerAddressFailure(AddressFailureKind.SOCKET,
                    unrelatedFrames,error)
            }catch(error:IOException) {
                throw PeerAddressFailure(AddressFailureKind.PROTOCOL_OR_IO,
                    unrelatedFrames,error)
            }
            when(event) {
                is A0PeerRendezvousWire.ServerEvent.Address -> return event
                A0PeerRendezvousWire.ServerEvent.UnsupportedAddress ->
                    throw PeerAddressFailure(AddressFailureKind.UNSUPPORTED_ADDRESS,
                        unrelatedFrames)
                A0PeerRendezvousWire.ServerEvent.PeerUnreachable ->
                    throw PeerAddressFailure(AddressFailureKind.PEER_UNREACHABLE,
                        unrelatedFrames)
                A0PeerRendezvousWire.ServerEvent.Unrelated -> unrelatedFrames++
            }
        }
        throw PeerAddressFailure(AddressFailureKind.BACKLOG_LIMIT,unrelatedFrames)
    }

    private fun requestIndirect(
        server:Socket,
        listener:ServerSocket,
        expectedAddress:InetAddress,
        peerUsername:String,
        deadline:Long
    ): Socket {
        checkNotCancelled()
        val token=SecureRandom().nextInt(Int.MAX_VALUE-1)+1
        server.getOutputStream().apply{
            write(A0PeerRendezvousWire.connectToPeer(token,peerUsername))
            flush()
        }
        listener.soTimeout=remainingTimeout(deadline)
        val socket=listener.accept()
        activeP=socket
        try {
            socket.soTimeout=remainingTimeout(deadline)
            if(!socket.inetAddress.address.contentEquals(expectedAddress.address)) {
                throw IOException("A0 incoming P differs from server-selected address")
            }
            A0PeerRendezvousWire.readIndirectPierce(socket.getInputStream(),token)
            checkNotCancelled()
            return socket
        } catch(error:Exception) {
            socket.close()
            throw error
        }
    }

    private fun remainingTimeout(deadline:Long): Int {
        val leftMillis=(deadline-System.nanoTime())/1_000_000L
        if(leftMillis<=0)throw SocketTimeoutException("A0 bounded peer operation expired")
        return minOf(4000L,leftMillis).toInt().coerceAtLeast(1)
    }

    private fun checkNotCancelled() {
        if(cancelled.get()||Thread.currentThread().isInterrupted) {
            throw IOException("A0 transfer cancelled")
        }
    }

    companion object {
        fun requireEndpoint(address:InetAddress,allowLoopbackForLocalTest:Boolean) {
            if(!A0PeerRendezvousWire.isRoutableIpv4(address.address) &&
                !(allowLoopbackForLocalTest &&
                    address.address.contentEquals(byteArrayOf(127,0,0,1)))) {
                throw IOException("A0 server supplied non-routable peer address")
            }
        }
    }
}
