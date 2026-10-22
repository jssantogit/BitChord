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
        authenticatedServer,localUsername,peerUsername,reference,listing,privateDirectory)

    fun runObservedOnce(
        authenticatedServer: Socket,
        localUsername: String,
        peerUsername: String,
        listing: A0OpenAudioCandidateGate.PublicPeerListing,
        privateDirectory: File
    ): A0VerifiedByteStager.Receipt = runInternal(
        authenticatedServer,localUsername,peerUsername,null,listing,privateDirectory)

    private fun runInternal(
        authenticatedServer: Socket,
        localUsername: String,
        peerUsername: String,
        reference: A0OpenAudioCandidateGate.ReviewedReference?,
        listing: A0OpenAudioCandidateGate.PublicPeerListing,
        privateDirectory: File
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
        val peerInit=A0PeerRendezvousWire.directPeerInit(localUsername)
        val getAddress=A0PeerRendezvousWire.getPeerAddress(peerUsername)
        val deadline=System.nanoTime()+15_000_000_000L

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
                val resolved=resolvePeer(authenticatedServer,peerUsername,deadline)
                requireEndpoint(resolved.address,allowLoopbackForLocalTest)
                val p = try {
                    checkNotCancelled()
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
                    requestIndirect(authenticatedServer,listener,resolved.address,
                        peerUsername,deadline)
                } catch (_:SocketTimeoutException) {
                    requestIndirect(authenticatedServer,listener,resolved.address,
                        peerUsername,deadline)
                }
                p.use{peer->
                    activeP=peer
                    checkNotCancelled()
                    session.queueUpload(peer.getOutputStream())
                    session.acceptUpload(peer.getInputStream(),peer.getOutputStream())
                    checkNotCancelled()
                    // A direct inbound F is not guaranteed behind carrier/
                    // residential NAT. Also accept a server-mediated code-18
                    // F offer from this exact peer; reply PierceFirewall on
                    // outbound F before reading FileTransferInit.
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
                        f.soTimeout=remainingTimeout(deadline)
                        if(!f.inetAddress.address.contentEquals(resolved.address.address)) {
                            throw IOException("A0 F peer does not match server-selected P address")
                        }
                        checkNotCancelled()
                        val receipt=session.receiveFile(
                            f.getInputStream(),f.getOutputStream(),privateDirectory,
                            isCancelled={cancelled.get()||Thread.currentThread().isInterrupted||
                                System.nanoTime()>=deadline}
                        )
                        if(cancelled.get()||Thread.currentThread().isInterrupted||
                            System.nanoTime()>=deadline) {
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

    private fun resolvePeer(server:Socket,peer:String,deadline:Long):
        A0PeerRendezvousWire.ServerEvent.Address {
        repeat(12) {
            checkNotCancelled()
            server.soTimeout=remainingTimeout(deadline)
            when(val event=A0PeerRendezvousWire.readServerEvent(
                server.getInputStream(),peer,pendingToken=1
            )) {
                is A0PeerRendezvousWire.ServerEvent.Address -> return event
                A0PeerRendezvousWire.ServerEvent.UnsupportedAddress ->
                    throw IOException("A0 server reported unsupported peer address")
                A0PeerRendezvousWire.ServerEvent.PeerUnreachable ->
                    throw IOException("A0 peer not reachable")
                A0PeerRendezvousWire.ServerEvent.Unrelated -> Unit
            }
        }
        throw IOException("A0 no peer address within bounded server frames")
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
