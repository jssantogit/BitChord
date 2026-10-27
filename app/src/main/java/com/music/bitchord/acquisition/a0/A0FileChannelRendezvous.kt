package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One bounded F-channel rendezvous, once P40/P41 approved a reviewed file.
 *
 * The uploader may connect F directly to the registered listen port; if our
 * device is behind NAT it may instead send server code-18 "F" and receive our
 * outbound PierceFirewall reply. The code must NOT accept F as a P connection.
 *
 * The sole server reader here starts only after the P-channel negotiation and
 * must be the ONLY reader of this authenticated server socket. Concurrent work
 * is restricted to an inbound F accept. Incoming F address MUST match the
 * address the authenticated server supplied for the selected peer. The raw F
 * TransferInit token is separately checked against the accepted P40 ticket by
 * A0ModernIncomingTransfer.receiveAfterFConnection.
 *
 * Neither peer usernames nor IPs nor tokens nor remote paths are logged.
 */
internal object A0FileChannelRendezvous {
    class TruncatedControlFrame : IOException("A0 truncated server F control message")

    fun awaitFileSocket(
        server: Socket,
        listener: ServerSocket,
        selectedPeerUsername: String,
        selectedPeerAddress: InetAddress,
        absoluteDeadlineNanos: Long,
        allowLoopbackForLocalTest: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onSocketActivated: (Socket) -> Unit = {}
    ): Socket {
        A0ServerAssociatedTransfer.requireEndpoint(
            selectedPeerAddress,allowLoopbackForLocalTest
        )
        val done=AtomicBoolean(false)
        val incoming=ArrayBlockingQueue<Socket>(1)
        val acceptError=AtomicReference<IOException?>()
        val acceptor=Executors.newSingleThreadExecutor { task ->
            Thread(task,"a0-file-inbound-accept").apply { isDaemon=true }
        }
        val priorServerTimeout=server.soTimeout
        val priorListenerTimeout=listener.soTimeout
        val readProgress=A0ProgressiveServerFrameReader(server.getInputStream())

        val future=acceptor.submit {
            try {
                listener.soTimeout=200
                var discardedUnexpectedSources=0
                while(!done.get() && !Thread.currentThread().isInterrupted &&
                    !isCancelled() && System.nanoTime()<absoluteDeadlineNanos) {
                    val connected=try {
                        listener.accept()
                    }catch (_:SocketTimeoutException) {
                        continue
                    }
                    if(!connected.inetAddress.address.contentEquals(selectedPeerAddress.address)){
                        connected.close() // NEVER admit an unrelated F socket.
                        // Public peers may connect while the selected upload is
                        // negotiating. One unrelated inbound attempt should
                        // not cancel a valid selected peer rendezvous.
                        discardedUnexpectedSources++
                        if(discardedUnexpectedSources>=8) {
                            acceptError.set(IOException("A0 excessive unsolicited F sources"))
                            break
                        }
                        continue
                    }
                    if(!incoming.offer(connected)) {
                        connected.close()
                    }
                    break
                }
            }catch (e:IOException) {
                if(!done.get() && !isCancelled())acceptError.set(e)
            }
        }
        fun checkState() {
            if(isCancelled() || Thread.currentThread().isInterrupted)
                throw IOException("A0 F rendezvous cancelled")
            acceptError.get()?.let{throw it}
            if(System.nanoTime()>=absoluteDeadlineNanos)
                throw SocketTimeoutException("A0 F rendezvous deadline expired")
        }
        try {
            while(true) {
                checkState()
                val ready=incoming.poll()
                if(ready!=null) {
                    onSocketActivated(ready)
                    checkState()
                    return ready
                }
                val leftMillis=(absoluteDeadlineNanos-System.nanoTime())/1_000_000L
                server.soTimeout=minOf(200L,leftMillis).toInt().coerceAtLeast(1)
                val evt=try {
                    A0IndirectFileOfferWire.readServerEvent(
                        ByteArrayInputStream(readProgress.readRawFrame()),
                        selectedPeerUsername,selectedPeerAddress
                    )
                }catch (_:SocketTimeoutException) {
                    // TCP frames may legitimately arrive in segments. The
                    // reader retains them while the bounded F deadline runs.
                    // Unfinished frames still fail closed at expiry.
                    continue
                }
                if(evt is A0IndirectFileOfferWire.Event.Offer) {
                    // Direct may have connected concurrently. Prefer it and
                    // never open a second file channel for a single P40 ticket.
                    val direct=incoming.poll()
                    if(direct!=null) {
                        onSocketActivated(direct)
                        checkState()
                        return direct
                    }
                    val indirect=Socket()
                    onSocketActivated(indirect)
                    try {
                        checkState()
                        val remainingMs=(absoluteDeadlineNanos-System.nanoTime())/1_000_000L
                        indirect.connect(
                            InetSocketAddress(evt.address,evt.port),
                            minOf(3000L,remainingMs).toInt().coerceAtLeast(1)
                        )
                        indirect.soTimeout=minOf(4000L,
                            ((absoluteDeadlineNanos-System.nanoTime())/1_000_000L)
                        ).toInt().coerceAtLeast(1)
                        checkState()
                        indirect.getOutputStream().apply {
                            write(A0IndirectSearchWire.pierceFirewall(evt.token))
                            flush()
                        }
                        checkState()
                        return indirect
                    }catch(e:Exception) {
                        indirect.close()
                        throw e
                    }
                }
            }
        }finally {
            done.set(true)
            acceptor.shutdownNow()
            runCatching{future.get(400,TimeUnit.MILLISECONDS)}
            // Unselected inbound sockets never survive another F path winning.
            while(true) {
                val extra=incoming.poll() ?: break
                extra.close()
            }
            runCatching{server.soTimeout=priorServerTimeout}
            runCatching{listener.soTimeout=priorListenerTimeout}
        }
    }
}
