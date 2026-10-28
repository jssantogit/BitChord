package com.music.bitchord.acquisition.a0

import android.content.Context
import android.os.Looper
import com.music.bitchord.download.SoulseekBatchPlan
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reusable READ-ONLY Soulseek discovery for a future opted-in Android worker.
 *
 * Uses the same proven A0 peer parsers, 12+128 indirect worker budget,
 * 12+128 direct collector, 160 ticket limit, 18s window, and two bounded
 * search variations as the manual Android pilot. A fresh authenticated
 * server socket is closed at the END of each search, BEFORE a separate
 * A0FreshAuthenticatedAutoTransport login for any P/F.
 *
 * Never writes audio, selects a peer, publishes media, or logs identities.
 * Not registered with the running DownloadService. Only devDebug login
 * is permitted by A0AutoAuthenticatedServer.
 */
internal class A0FreshAutoSearchTransport(
    context:Context,
    private val generation:Long,
    private val quality:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
):A0AutoDiscoveryCoordinator.SearchPort {
    private val app=context.applicationContext
    private val closed=AtomicBoolean(false)
    private val activePeers=ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var activeServer:Socket?=null
    @Volatile private var activeListener:ServerSocket?=null
    @Volatile private var activeHandoff:A0ObservedCandidateHandoff?=null
    @Volatile private var activeDirect:A0DirectPeerSearchCollector?=null
    @Volatile private var activeWorkers:A0PeerSearchWorkers?=null
    @Volatile private var activeReaders:ExecutorService?=null

    override fun search(
        entry:SoulseekBatchPlan.Entry,
        releaseSlots:List<SoulseekBatchPlan.Entry>,
        isCancelled:()->Boolean,
    ):A0AutoDiscoveryCoordinator.SearchResult {
        check(Looper.myLooper()!=Looper.getMainLooper()) {
            "Soulseek automatic discovery cannot block Android main thread"
        }
        fun stopped()=closed.get() || isCancelled() ||
            !SoulseekActiveSession.isCurrent(generation) ||
            Thread.currentThread().isInterrupted
        val valid=releaseSlots.size in 1..SoulseekBatchPlan.MAX_TRACKS &&
            releaseSlots.map{it.trackId}.distinct().size==releaseSlots.size &&
            entry in releaseSlots &&
            releaseSlots.all{it.trackId.isNotBlank() && it.query.isNotBlank()}
        if(stopped() || !valid)return A0AutoDiscoveryCoordinator.SearchResult.Stop
        val normalized=runCatching {
            A0SearchQuery.normalize(entry.query)
        }.getOrElse {return A0AutoDiscoveryCoordinator.SearchResult.Stop}
        var session:A0AutoAuthenticatedServer.Session?=null
        var listener:ServerSocket?=null
        var handoff:A0ObservedCandidateHandoff?=null
        var direct:A0DirectPeerSearchCollector?=null
        var workers:A0PeerSearchWorkers?=null
        var readers:ExecutorService?=null
        val readerGate=A0SearchReaderGate()
        val readerFailed=AtomicBoolean(false)
        try {
            session=A0AutoAuthenticatedServer.open(
                app,generation,::stopped,onSocketReady={activeServer=it})
            val connection=checkNotNull(session).socket
            listener=ServerSocket(0,128)
            activeListener=listener
            listener.soTimeout=750
            val token=SecureRandom().nextInt(Int.MAX_VALUE-1)+1
            handoff=A0ObservedCandidateHandoff(
                connection,checkNotNull(session).username,token,
                searchPhrase=entry.query,saveFormatsOnly=true,
                expectedDurationSeconds=entry.durationSeconds,
                requestedAlbum=entry.album,qualityProfile=quality,
                collectReleaseShadow=true,
                releaseQueries=releaseSlots.map {it.query},
            )
            activeHandoff=handoff
            val holder=checkNotNull(handoff)
            connection.getOutputStream().apply {
                write(A0OneShotPublicSearchWire.setWaitPort(checkNotNull(listener).localPort))
                write(A0OneShotPublicSearchWire.fileSearch(token,normalized))
                flush()
            }
            if(stopped())return A0AutoDiscoveryCoordinator.SearchResult.Stop
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(18)
            val gate=A0PeerOfferGate(160)
            workers=A0PeerSearchWorkers(queueCapacity=128,parallelism=12)
            activeWorkers=workers
            val peerWorkers=checkNotNull(workers)
            direct=A0DirectPeerSearchCollector { peer ->
                if(!stopped() && readerGate.isOpen()) {
                    try {
                        peer.soTimeout=3500
                        A0OneShotPublicSearchWire.readOneDirectPeerReply(
                            peer.getInputStream(),token
                        ) { frame,claimed ->
                            if(!holder.offerSearchResult(frame,claimed))
                                throw IOException("Soulseek direct claim was invalid")
                        }
                    }catch(_:IOException) {
                        // One malformed or slow untrusted peer cannot poison
                        // an otherwise useful release-wide discovery.
                    }
                }
            }
            activeDirect=direct
            val directCollector=checkNotNull(direct)
            readers=Executors.newFixedThreadPool(2)
            activeReaders=readers
            val executor=checkNotNull(readers)
            val openListener=checkNotNull(listener)
            executor.execute {
                while(readerGate.isOpen() && !stopped() &&
                    !openListener.isClosed && System.nanoTime()<deadline) {
                    try {
                        val peer=openListener.accept()
                        directCollector.offer(peer)
                    }catch(_:SocketTimeoutException) {
                        // Keep listening for the complete 18-second window.
                    }catch(_:IOException) {
                        if(readerGate.isOpen() && !stopped())
                            readerFailed.set(true)
                        break
                    }
                }
            }
            executor.execute {
                val framed=A0ProgressiveServerFrameReader(
                    connection.getInputStream())
                connection.soTimeout=750
                var accepted=0
                try {
                    while(readerGate.isOpen() && !stopped() &&
                        System.nanoTime()<deadline && accepted<160) {
                        val offer=try {
                            framed.readEvent()
                        }catch(_:SocketTimeoutException) {continue}
                        catch(_:A0IndirectSearchWire.RejectedPeerOffer) {continue}
                        if(offer==null || !readerGate.isOpen())continue
                        if(!gate.accept(offer.username,offer.token))continue
                        val submitted=peerWorkers.submit {
                            if(!stopped() && readerGate.isOpen()) {
                                Socket().use { peer ->
                                    activePeers.add(peer)
                                    try {
                                        peer.connect(InetSocketAddress(
                                            offer.address,offer.port),1800)
                                        peer.soTimeout=2300
                                        peer.getOutputStream().apply {
                                            write(A0IndirectSearchWire.pierceFirewall(offer.token))
                                            flush()
                                        }
                                        A0IndirectSearchWire.readIndirectReply(
                                            peer.getInputStream(),token,offer.username
                                        ) { frame,claimed ->
                                            if(!holder.offerSearchResult(frame,claimed))
                                                throw IOException("Invalid Soulseek indirect reply")
                                        }
                                    }catch(_:IOException) {
                                        // Ignore one peer; retain validated replies.
                                    }finally {
                                        activePeers.remove(peer)
                                    }
                                }
                            }
                        }
                        if(submitted)accepted++
                        else gate.releaseUnsubmitted(offer.username,offer.token)
                    }
                    if(!stopped() && !framed.finishPendingFrame())
                        readerFailed.set(true)
                }catch(_:IOException) {
                    if(!stopped())readerFailed.set(true)
                }
            }

            val recovery=A0SearchRecoveryPlan(entry.query)
            val sent=mutableSetOf(normalized)
            val marks=mutableSetOf<Int>()
            while(System.nanoTime()<deadline && !stopped()) {
                val elapsed=TimeUnit.NANOSECONDS.toMillis(
                    18L.let {TimeUnit.SECONDS.toNanos(it)}-(deadline-System.nanoTime()))
                for(at in listOf(6000,12000)) {
                    if(elapsed>=at && marks.add(at)) {
                        val variant=recovery.next(
                            at.toLong(),holder.hasRelevantCandidateForSearchRecovery(),sent)
                        if(variant!=null && !stopped()) {
                            connection.getOutputStream().apply {
                                write(A0OneShotPublicSearchWire.fileSearch(token,variant))
                                flush()
                            }
                            sent.add(variant)
                        }
                    }
                }
                Thread.sleep(200)
            }
            readerGate.stop()
            // Unlike the manual path, the authenticated server socket is
            // NEVER handed to P/F; still drain readers before taking a
            // complete immutable snapshot of observations.
            val joined=runCatching {
                A0SearchReaderHandoff.stop(
                    executor,
                    closeSearchSockets={
                        runCatching {openListener.close()}
                        directCollector.closeSockets()
                        activePeers.toList().forEach {runCatching{it.close()}}
                    },timeoutMillis=5000)
                directCollector.stop(4500) && peerWorkers.stop(4500)
            }.getOrDefault(false)
            if(!joined || readerFailed.get() || stopped())
                return A0AutoDiscoveryCoordinator.SearchResult.Stop
            holder.markReadersStopped()
            val choices=holder.releaseShadowChoices()
            return if(choices.isEmpty())
                A0AutoDiscoveryCoordinator.SearchResult.NoReply
            else A0AutoDiscoveryCoordinator.SearchResult.Found(choices)
        }catch(_:Exception) {
            return A0AutoDiscoveryCoordinator.SearchResult.Stop
        }finally {
            readerGate.stop()
            activePeers.toList().forEach {runCatching{it.close()}}
            runCatching{listener?.close()}
            runCatching{direct?.cancel()}
            runCatching{workers?.cancel()}
            readers?.shutdownNow()
            handoff?.cancel()
            session?.close()
            activeHandoff=null
            activeListener=null
            activeDirect=null
            activeWorkers=null
            activeReaders=null
            activeServer=null
        }
    }

    override fun close() {
        if(!closed.compareAndSet(false,true))return
        runCatching{activeHandoff?.cancel()}
        runCatching{activeListener?.close()}
        runCatching{activeDirect?.cancel()}
        runCatching{activeWorkers?.cancel()}
        activePeers.toList().forEach {runCatching{it.close()}}
        runCatching{activeServer?.close()}
        activeReaders?.shutdownNow()
    }
}
