package com.music.bitchord.acquisition.a0

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val folderProbe=A0FreshPeerFolderTransport(app,generation,quality)
    // One release-wide search per explicit batch, never a repeated query storm.
    private val releaseWideSent=ConcurrentHashMap.newKeySet<String>()
    private val releaseWideSkipped=AtomicInteger()
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
        val traceScope=A0AcquisitionDiagnostics.newScope()
        val traceStart=android.os.SystemClock.elapsedRealtime()
        A0AcquisitionDiagnostics.emit(traceScope,
            A0AcquisitionDiagnostics.Phase.SEARCH_START,
            count=releaseSlots.size,
            slot=releaseSlots.indexOf(entry))
        val valid=releaseSlots.size in 1..SoulseekBatchPlan.MAX_TRACKS &&
            releaseSlots.map{it.trackId}.distinct().size==releaseSlots.size &&
            entry in releaseSlots &&
            releaseSlots.all{it.trackId.isNotBlank() && it.query.isNotBlank()}
        if(stopped() || !valid) {
            A0AcquisitionDiagnostics.emit(traceScope,
                A0AcquisitionDiagnostics.Phase.SEARCH_STOP,
                reason=if(stopped())
                    A0AcquisitionDiagnostics.Fault.CANCELLED
                else A0AcquisitionDiagnostics.Fault.ARGUMENT)
            return A0AutoDiscoveryCoordinator.SearchResult.Stop
        }
        val normalized=runCatching {
            A0SearchQuery.canonicalArtistTitle(entry.query)
                ?:throw IllegalArgumentException("Invalid canonical search")
        }.getOrElse {
            A0AcquisitionDiagnostics.emit(traceScope,
                A0AcquisitionDiagnostics.Phase.SEARCH_STOP,
                reason=A0AcquisitionDiagnostics.Fault.ARGUMENT,
                elapsedMillis=android.os.SystemClock.elapsedRealtime()-traceStart)
            return A0AutoDiscoveryCoordinator.SearchResult.QueryRejected
        }
        // Real release searches often return no result for a rare edition
        // but answer the artist+album query with eligible sibling files.
        // Ask that release-wide query FIRST once per batch (>=4 slots), so
        // the six-second fallback delay no longer blocks sibling discovery.
        // The original canonical song still owns ALL identity/quality gates.
        val sameReleaseCount=A0ReleaseInitialQueryPolicy.cohortSize(
            entry,releaseSlots)
        // A large public album query can overflow the bounded release shadow
        // and irreversibly mark numerous siblings INCOMPLETE_SAMPLE.
        // Disable it for big cohorts BOTH as initial query and fallback.
        val recovery=A0SearchRecoveryPlan(entry.query,entry.album,
            releaseWide=A0ReleaseInitialQueryPolicy.permitsAlbumWide(
                sameReleaseCount))
        val initialQuery=A0ReleaseInitialQueryPolicy.choose(
            normalized,recovery.releaseWideQuery,sameReleaseCount,releaseWideSent)
        // A single reservation covers this session's initial query and up
        // to two optional fallbacks. Waiting occurs BEFORE authentication,
        // so we do not hold an idle Soulseek server socket for minutes.
        val rateBudget=A0GlobalSearchWindowBudget.shared
        var searchLease:A0SearchWindowBudget.Lease?=null
        var searchThrottleWaitMs=0L
        var session:A0AutoAuthenticatedServer.Session?=null
        var listener:ServerSocket?=null
        var handoff:A0ObservedCandidateHandoff?=null
        var direct:A0DirectPeerSearchCollector?=null
        var workers:A0PeerSearchWorkers?=null
        var readers:ExecutorService?=null
        val readerGate=A0SearchReaderGate()
        val readerFailed=AtomicBoolean(false)
        val workerStages=A0PeerWorkStageCounts()
        try {
            var waitAnnounced=false
            while(!stopped() && searchLease==null) {
                val now=android.os.SystemClock.elapsedRealtime()
                searchLease=rateBudget.tryAcquireUpTo(3,now)
                if(searchLease==null) {
                    val delay=rateBudget.waitMillis(1,now).coerceIn(1L,1000L)
                    if(!waitAnnounced) {
                        waitAnnounced=true
                        com.music.bitchord.data.DebugLog.d("BitChord",
                            "soulseek_auto_search rate_budget_wait=true reserved_up_to=3")
                    }
                    Thread.sleep(delay)
                    searchThrottleWaitMs+=delay
                }
            }
            if(stopped())return A0AutoDiscoveryCoordinator.SearchResult.Stop
            session=A0AutoAuthenticatedServer.open(
                app,generation,::stopped,onSocketReady={activeServer=it},
                onStage={stage ->
                    A0AcquisitionDiagnostics.emit(traceScope,
                        A0AcquisitionDiagnostics.Phase.SEARCH_LOGIN_STAGE,
                        reason=stage,
                        elapsedMillis=android.os.SystemClock.elapsedRealtime()-traceStart)
                })
            A0AcquisitionDiagnostics.emit(traceScope,
                A0AcquisitionDiagnostics.Phase.SEARCH_LOGIN_OK,
                elapsedMillis=android.os.SystemClock.elapsedRealtime()-traceStart)
            val connection=checkNotNull(session).socket
            listener=ServerSocket(0,128)
            activeListener=listener
            listener.soTimeout=750
            val token=SecureRandom().nextInt(Int.MAX_VALUE-1)+1
            val tickets=A0SearchTicketRegistry(token)
            val validDirect=AtomicInteger()
            val validIndirect=AtomicInteger()
            val directAccepted=AtomicInteger()
            val directDropped=AtomicInteger()
            val directFailed=AtomicInteger()
            val indirectOffers=AtomicInteger()
            val rejectedServerOffers=AtomicInteger()
            val ignoredServerFrames=AtomicInteger()
            val indirectSubmitted=AtomicInteger()
            val indirectConnected=AtomicInteger()
            val indirectFailed=AtomicInteger()
            val indirectConnectFailed=AtomicInteger()
            val indirectConnectTimeout=AtomicInteger()
            val indirectConnectError=AtomicInteger()
            val indirectConnectNoRoute=AtomicInteger()
            val indirectConnectOther=AtomicInteger()
            val indirectInitFailed=AtomicInteger()
            val indirectReplyFailed=AtomicInteger()
            handoff=A0ObservedCandidateHandoff(
                connection,checkNotNull(session).username,token,
                searchPhrase=entry.query,saveFormatsOnly=true,
                expectedDurationSeconds=entry.durationSeconds,
                requestedAlbum=entry.album,qualityProfile=quality,
                collectReleaseShadow=true,
                releaseQueries=releaseSlots.map {it.query},
                collectManualCandidates=false,
                acceptedSearchTokens=tickets.accepted,
            )
            activeHandoff=handoff
            A0AcquisitionDiagnostics.emit(traceScope,
                A0AcquisitionDiagnostics.Phase.SEARCH_READER_READY,
                elapsedMillis=android.os.SystemClock.elapsedRealtime()-traceStart)
            val holder=checkNotNull(handoff)
            val maxPublishMillis=AtomicLong()
            val slowPublishCount=AtomicInteger()
            fun publishParsed(reply:A0SoulseekSearchResponse,claimed:String):Boolean {
                val started=System.nanoTime()
                return try {
                    holder.offerParsed(reply,claimed)
                } finally {
                    val elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
                    maxPublishMillis.updateAndGet { maxOf(it,elapsed) }
                    if(elapsed>=500)slowPublishCount.incrementAndGet()
                }
            }
            connection.getOutputStream().apply {
                write(A0OneShotPublicSearchWire.setWaitPort(checkNotNull(listener).localPort))
                check(checkNotNull(searchLease).commit(
                    android.os.SystemClock.elapsedRealtime()))
                write(A0OneShotPublicSearchWire.fileSearch(token,initialQuery.query))
                flush()
            }
            if(initialQuery.kind==A0ReleaseInitialQueryPolicy.Kind.ALBUM_WIDE)
                releaseWideSent.add(initialQuery.query)
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
                            peer.getInputStream(),token,
                            onValidatedParsedReply={ parsed ->
                                if(!publishParsed(parsed,parsed.peerUsername))
                                    throw IOException("Soulseek direct claim was invalid")
                                validDirect.incrementAndGet()
                                tickets.creditValidated(parsed.token,parsed.files.size)
                            },
                            acceptedSearchTokens=tickets.accepted
                        )
                    }catch(_:IOException) {
                        directFailed.incrementAndGet()
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
                        directAccepted.incrementAndGet()
                        if(!directCollector.offer(peer))
                            directDropped.incrementAndGet()
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
                            framed.readEvent { _ -> ignoredServerFrames.incrementAndGet() }
                        }catch(_:SocketTimeoutException) {continue}
                        catch(_:A0IndirectSearchWire.RejectedPeerOffer) {
                            rejectedServerOffers.incrementAndGet()
                            continue
                        }
                        if(offer==null || !readerGate.isOpen())continue
                        indirectOffers.incrementAndGet()
                        if(!gate.accept(offer.username,offer.token))continue
                        val submitted=peerWorkers.submit {
                            if(!stopped() && readerGate.isOpen()) {
                                Socket().use { peer ->
                                    activePeers.add(peer)
                                    var connected=false
                                    var initSent=false
                                    try {
                                        workerStages.inStage(
                                            A0PeerWorkStageCounts.Stage.CONNECTING) {
                                            peer.connect(InetSocketAddress(
                                                offer.address,offer.port),1800)
                                            connected=true
                                            indirectConnected.incrementAndGet()
                                            peer.soTimeout=2300
                                            peer.getOutputStream().apply {
                                                write(A0IndirectSearchWire.pierceFirewall(offer.token))
                                                flush()
                                            }
                                            initSent=true
                                        }
                                        workerStages.inStage(
                                            A0PeerWorkStageCounts.Stage.READING) {
                                            A0IndirectSearchWire.readIndirectReply(
                                                peer.getInputStream(),token,offer.username,
                                                onValidatedParsedReply={ parsed ->
                                                    workerStages.inStage(
                                                        A0PeerWorkStageCounts.Stage.PUBLISHING) {
                                                        if(!publishParsed(parsed,offer.username))
                                                            throw IOException("Invalid Soulseek indirect reply")
                                                        validIndirect.incrementAndGet()
                                                        tickets.creditValidated(parsed.token,parsed.files.size)
                                                    }
                                                },
                                                acceptedSearchTokens=tickets.accepted
                                            )
                                        }
                                    }catch(error:IOException) {
                                        indirectFailed.incrementAndGet()
                                        // Connection / peer-init / response are
                                        // distinct failure modes. Only bounded
                                        // counts are logged, never identities.
                                        when(A0IndirectPeerFailureStage.classify(
                                            connected,initSent)) {
                                            A0IndirectPeerFailureStage.Phase.CONNECT -> {
                                                indirectConnectFailed.incrementAndGet()
                                                when(A0IndirectPeerFailureStage.connectCause(error)) {
                                                    A0IndirectPeerFailureStage.ConnectCause.TIMEOUT ->
                                                        indirectConnectTimeout.incrementAndGet()
                                                    A0IndirectPeerFailureStage.ConnectCause.CONNECT_ERROR ->
                                                        indirectConnectError.incrementAndGet()
                                                    A0IndirectPeerFailureStage.ConnectCause.NO_ROUTE ->
                                                        indirectConnectNoRoute.incrementAndGet()
                                                    A0IndirectPeerFailureStage.ConnectCause.OTHER ->
                                                        indirectConnectOther.incrementAndGet()
                                                }
                                            }
                                            A0IndirectPeerFailureStage.Phase.INIT ->
                                                indirectInitFailed.incrementAndGet()
                                            A0IndirectPeerFailureStage.Phase.RESPONSE ->
                                                indirectReplyFailed.incrementAndGet()
                                        }
                                        // Ignore one peer; retain validated replies.
                                    }finally {
                                        activePeers.remove(peer)
                                    }
                                }
                            }
                        }
                        if(submitted) {
                            accepted++
                            indirectSubmitted.incrementAndGet()
                        } else gate.releaseUnsubmitted(offer.username,offer.token)
                    }
                    if(!stopped() && !framed.finishPendingFrame())
                        readerFailed.set(true)
                }catch(_:IOException) {
                    if(!stopped())readerFailed.set(true)
                }
            }

            val sent=mutableSetOf(initialQuery.query)
            val marks=mutableSetOf<Int>()
            var nextCompletionCheckMs=8000L
            var budgetSkippedFallbacks=0
            var fallbackOneKind=A0SearchRecoveryPlan.VariantKind.NONE
            var fallbackTwoKind=A0SearchRecoveryPlan.VariantKind.NONE
            var releaseWideSkipCounted=false
            while(System.nanoTime()<deadline && !stopped()) {
                val elapsed=TimeUnit.NANOSECONDS.toMillis(
                    18L.let {TimeUnit.SECONDS.toNanos(it)}-(deadline-System.nanoTime()))
                for(at in listOf(6000,12000)) {
                    if(elapsed>=at && marks.add(at)) {
                        val found=holder.hasAutoReadyCandidateForSearchRecovery(entry,quality)
                        // Use the released shadow to discover siblings even
                        // when the current song already has a safe candidate.
                        // No extra title-only searches in that case.
                        val missingSiblings=releaseSlots.size>=4 &&
                            recovery.releaseWideQuery!=null &&
                            !A0AutoSearchEarlyExitPolicy.hasCompleteEligibleRelease(
                                releaseSlots,quality,holder.releaseShadowSnapshot())
                        val releaseQuery=recovery.releaseWideQuery
                        if(!releaseWideSkipCounted &&
                            releaseQuery!=initialQuery.query &&
                            (missingSiblings || !found) &&
                            releaseQuery!=null && releaseQuery in releaseWideSent) {
                            releaseWideSkipped.incrementAndGet()
                            releaseWideSkipCounted=true
                        }
                        val variant=recovery.next(
                            at.toLong(),found,sent,missingSiblings=missingSiblings,
                            excludedQueries=releaseWideSent)
                        if(variant!=null && !stopped()) {
                            val lease=checkNotNull(searchLease)
                            if(lease.remaining()==0 &&
                                !lease.reserveOneMore(android.os.SystemClock.elapsedRealtime())) {
                                // No capacity: leave the unsent album query
                                // available to another slot. Never wait on
                                // the authenticated socket or exceed 3 sends.
                                budgetSkippedFallbacks++
                            } else {
                                // Register a unique correlation ticket before
                                // sending each fallback wire query.
                                val variantTicket=generateSequence {
                                    SecureRandom().nextInt(Int.MAX_VALUE-1)+1
                                }.take(8).firstOrNull(tickets::register)
                                if(variantTicket==null)continue
                                check(checkNotNull(searchLease).commit(
                                    android.os.SystemClock.elapsedRealtime()))
                                connection.getOutputStream().apply {
                                    write(A0OneShotPublicSearchWire.fileSearch(variantTicket,variant))
                                    flush()
                                }
                                sent.add(variant)
                                // Diagnostics correlate response counts to
                                // broad vs album-qualified search strategies,
                                // NEVER exposing the actual wire query.
                                if(sent.size==2)
                                    fallbackOneKind=recovery.variantKind(variant)
                                else if(sent.size==3)
                                    fallbackTwoKind=recovery.variantKind(variant)
                                if(variant==recovery.releaseWideQuery)
                                    releaseWideSent.add(variant)
                                A0AcquisitionDiagnostics.emit(traceScope,
                                    A0AcquisitionDiagnostics.Phase.SEARCH_RECOVERY_SENT,
                                    elapsedMillis=android.os.SystemClock.elapsedRealtime()-traceStart,
                                    count=sent.size)
                            }
                        }
                    }
                }
                // Keep ordinary 18-second recall if any sibling is missing,
                // ambiguous or clipped. Only a complete independently
                // eligible 2+ track release may finish at >=8 seconds.
                if(releaseSlots.size>1 && elapsed>=nextCompletionCheckMs) {
                    nextCompletionCheckMs=elapsed+2000L
                    if(A0AutoSearchEarlyExitPolicy.canFinish(
                        elapsed,releaseSlots,quality,holder.releaseShadowSnapshot()
                    )) {
                        Log.d("BitChord","soulseek_auto_search early_stop=FULL_RELEASE_COVERAGE" +
                            " elapsed_ms=${elapsed.coerceIn(0,18000)}")
                        break
                    }
                }
                Thread.sleep(200)
            }
            readerGate.stop()
            // Unlike the manual path, the authenticated server socket is
            // NEVER handed to P/F; still drain readers before taking a
            // complete immutable snapshot of observations.
            val readerJoined=runCatching {
                A0SearchReaderHandoff.stop(
                    executor,
                    closeSearchSockets={
                        runCatching {openListener.close()}
                        directCollector.closeSockets()
                        activePeers.toList().forEach {runCatching{it.close()}}
                    },timeoutMillis=5000)
                true
            }.getOrDefault(false)
            // Always join BOTH peer pools: short-circuiting on a slow direct
            // pool used to skip indirect cleanup and strand the next slots.
            val directJoined=directCollector.stop(4500)
            // The bulk of inbound replies can already be parsed while a
            // few large release shadows finish the synchronized bounded
            // in-memory update. Allow a strict 8s drain after closing P
            // sockets; never reuse a still-running snapshot for transfer.
            val indirectJoined=peerWorkers.stop(8000)
            val joined=readerJoined && directJoined && indirectJoined
            if(!joined || readerFailed.get() || stopped()) {
                val revoked=stopped()
                val peerStats=peerWorkers.snapshot()
                val directStats=directCollector.workerSnapshot()
                A0AcquisitionDiagnostics.emit(traceScope,
                    A0AcquisitionDiagnostics.Phase.SEARCH_STOP,
                    reason=if(revoked) A0AcquisitionDiagnostics.Fault.CANCELLED
                        else A0AcquisitionDiagnostics.Fault.STATE,
                    elapsedMillis=android.os.SystemClock.elapsedRealtime()-traceStart)
                Log.d("BitChord","soulseek_auto_search stopped " +
                    "joined=$joined reader_joined=$readerJoined " +
                    "direct_joined=$directJoined indirect_joined=$indirectJoined" +
                    " direct_active=${directStats.activeNow.coerceIn(0,12)}" +
                    " direct_queued=${directStats.queuedNow.coerceIn(0,128)}" +
                    " indirect_active=${peerStats.activeNow.coerceIn(0,12)}" +
                    " indirect_queued=${peerStats.queuedNow.coerceIn(0,128)}" +
                    " indirect_open_sockets=${activePeers.count { !it.isClosed }.coerceIn(0,12)}" +
                    " indirect_stages=${workerStages.summary()}" +
                    " max_publish_ms=${maxPublishMillis.get().coerceIn(0,60000)}" +
                    " slow_publish_replies=${slowPublishCount.get().coerceIn(0,160)}" +
                    " readerFailed=${readerFailed.get()} revoked=$revoked")
                // Failed peer-pool drain is not necessarily a protocol or
                // account failure. Discard its incomplete snapshot while
                // allowing other slots to start a FRESH authenticated search.
                return if(!joined && !readerFailed.get() && !revoked)
                    A0AutoDiscoveryCoordinator.SearchResult.TeardownIncomplete
                else A0AutoDiscoveryCoordinator.SearchResult.Stop
            }
            holder.markReadersStopped()
            val snapshot=holder.releaseShadowSnapshot()
            val counts=holder.screening()
            val replyByTicket=tickets.responseCounts()
            val filesByTicket=tickets.publicFileCounts()
            val successWorkers=peerWorkers.snapshot()
            val reachability=A0SearchReachabilityDiagnosis.classify(
                directAccepted.get(),indirectOffers.get(),indirectConnected.get(),
                counts.responses,snapshot.choices.size,
                rejectedServerOffers.get())
            Log.d("BitChord","soulseek_auto_search replies=${snapshot.choices.size}" +
                " initial_query_kind=${initialQuery.kind.name}" +
                " tickets_sent=${tickets.accepted.size.coerceIn(1,3)}" +
                " rate_budget_wait_ms=${searchThrottleWaitMs.coerceIn(0,300000)}" +
                " fallback_budget_skipped=${budgetSkippedFallbacks.coerceIn(0,2)}" +
                " initial_replies=${replyByTicket[0]}" +
                " fallback_1_replies=${replyByTicket[1]}" +
                " fallback_2_replies=${replyByTicket[2]}" +
                " initial_public_files=${filesByTicket[0]}" +
                " fallback_1_public_files=${filesByTicket[1]}" +
                " fallback_2_public_files=${filesByTicket[2]}" +
                " fallback_1_kind=${fallbackOneKind.name}" +
                " fallback_2_kind=${fallbackTwoKind.name}" +
                " valid_direct=${validDirect.get().coerceIn(0,160)}" +
                " valid_indirect=${validIndirect.get().coerceIn(0,160)}" +
                " release_queries=${releaseWideSent.size.coerceIn(0,8)}" +
                " release_dedup=${releaseWideSkipped.get().coerceIn(0,64)}" +
                " reachability=${reachability.name}" +
                " direct_accepted=${directAccepted.get().coerceIn(0,512)}" +
                " direct_dropped=${directDropped.get().coerceIn(0,512)}" +
                " direct_failed=${directFailed.get().coerceIn(0,512)}" +
                " indirect_offers=${indirectOffers.get().coerceIn(0,512)}" +
                " server_offers_rejected=${rejectedServerOffers.get().coerceIn(0,512)}" +
                " server_frames_ignored=${ignoredServerFrames.get().coerceIn(0,512)}" +
                " indirect_submitted=${indirectSubmitted.get().coerceIn(0,512)}" +
                " indirect_connected=${indirectConnected.get().coerceIn(0,512)}" +
                " indirect_failed=${indirectFailed.get().coerceIn(0,512)}" +
                " indirect_connect_failed=${indirectConnectFailed.get().coerceIn(0,512)}" +
                " indirect_connect_timeout=${indirectConnectTimeout.get().coerceIn(0,512)}" +
                " indirect_connect_error=${indirectConnectError.get().coerceIn(0,512)}" +
                " indirect_connect_no_route=${indirectConnectNoRoute.get().coerceIn(0,512)}" +
                " indirect_connect_other=${indirectConnectOther.get().coerceIn(0,512)}" +
                " indirect_init_failed=${indirectInitFailed.get().coerceIn(0,512)}" +
                " indirect_reply_failed=${indirectReplyFailed.get().coerceIn(0,512)}" +
                " indirect_workers_started=${successWorkers.started.coerceIn(0,512)}" +
                " indirect_workers_not_started=${(successWorkers.accepted-successWorkers.started).coerceIn(0,512)}" +
                " indirect_queue_peak=${successWorkers.peakQueued.coerceIn(0,128)}" +
                " truncated_slots=${snapshot.truncatedSlots.size}" +
                " raw_responses=${counts.responses} public_files=${counts.publicFiles}" +
                " admitted_before_limit=${counts.validBeforeLimit}" +
                " shadow_title_filtered=${counts.shadowTitleFiltered}" +
                " no_slot=${counts.noSlot} unsafe_path=${counts.unsafePath}" +
                " unsupported_format=${counts.unsupportedFormat}" +
                " duplicate=${counts.duplicates}" +
                " manual_limit=${counts.capacityDropped}" +
                " peer_limit=${counts.perPeerDropped}" +
                " shadow_evicted=${snapshot.shadowEvicted}" +
                " shadow_global_dropped=${snapshot.shadowDroppedAtGlobalLimit}" +
                " shadow_peer_dropped=${snapshot.shadowDroppedAtPeerLimit}" +
                " max_publish_ms=${maxPublishMillis.get().coerceIn(0,60000)}" +
                " slow_publish_replies=${slowPublishCount.get().coerceIn(0,160)}")
            return if(snapshot.choices.isEmpty() && snapshot.truncatedSlots.isEmpty()) {
                if(counts.responses>0)
                    A0AutoDiscoveryCoordinator.SearchResult.ScreenedOut
                else A0AutoDiscoveryCoordinator.SearchResult.NoReply
            } else A0AutoDiscoveryCoordinator.SearchResult.Found(
                snapshot.choices,snapshot.truncatedSlots)
        }catch(error:Exception) {
            A0AcquisitionDiagnostics.emit(traceScope,
                A0AcquisitionDiagnostics.Phase.SEARCH_EXCEPTION,
                reason=A0AcquisitionDiagnostics.fault(error),
                elapsedMillis=android.os.SystemClock.elapsedRealtime()-traceStart)
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
            searchLease?.close()
            activeHandoff=null
            activeListener=null
            activeDirect=null
            activeWorkers=null
            activeReaders=null
            activeServer=null
        }
    }

    override fun inspectPublishedFolder(
        source:A0ObservedCandidateHandoff.Choice,
        releaseSlots:List<SoulseekBatchPlan.Entry>,
        isCancelled:()->Boolean,
    ):A0AutoDiscoveryCoordinator.SearchResult = folderProbe.inspect(
        source,releaseSlots,{closed.get() || isCancelled()})

    override fun close() {
        if(!closed.compareAndSet(false,true))return
        runCatching{folderProbe.close()}
        runCatching{activeHandoff?.cancel()}
        runCatching{activeListener?.close()}
        runCatching{activeDirect?.cancel()}
        runCatching{activeWorkers?.cancel()}
        activePeers.toList().forEach {runCatching{it.close()}}
        runCatching{activeServer?.close()}
        activeReaders?.shutdownNow()
    }
}
