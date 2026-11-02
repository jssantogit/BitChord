package com.music.bitchord.acquisition.a0

import android.content.Context
import android.os.Looper
import com.music.bitchord.download.SoulseekBatchPlan
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One bounded, read-only folder browse from the SAME source whose sibling
 * successfully passed A3. Not a general browse UI, crawl, or remote directory
 * enumerator. A peer folder response is never sufficient transfer authority.
 *
 * For now an unreachable/direct-incompatible peer fails back to ordinary
 * discovery. Never silently use another peer or a similarly named folder.
 */
internal class A0FreshPeerFolderTransport(
    context:Context,
    private val generation:Long,
    private val quality:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
):AutoCloseable {
    private val app=context.applicationContext
    private val closed=AtomicBoolean(false)
    @Volatile private var activeP:Socket?=null
    @Volatile private var activeServer:Socket?=null

    fun inspect(
        source:A0ObservedCandidateHandoff.Choice,
        slots:List<SoulseekBatchPlan.Entry>,
        cancelled:()->Boolean,
    ):A0AutoDiscoveryCoordinator.SearchResult {
        check(Looper.myLooper()!=Looper.getMainLooper())
        fun stopped()=closed.get() || cancelled() ||
            Thread.currentThread().isInterrupted ||
            !SoulseekActiveSession.isCurrent(generation)
        if(stopped() || slots.size !in 2..SoulseekBatchPlan.MAX_TRACKS)
            return A0AutoDiscoveryCoordinator.SearchResult.NoReply
        val raw=source.remotePath
        val sep=maxOf(raw.lastIndexOf('/'),raw.lastIndexOf('\\'))
        if(sep<=0)return A0AutoDiscoveryCoordinator.SearchResult.NoReply
        val folder=raw.substring(0,sep)
        val token=SecureRandom().nextInt(Int.MAX_VALUE-1)+1
        val request=try {A0PeerFolderContentsWire.request(token,folder)}
            catch(_:IllegalArgumentException) {
                return A0AutoDiscoveryCoordinator.SearchResult.NoReply
            }
        val started=System.nanoTime()
        // Fresh authentication is included in the entire probe budget.
        // The socket's short read timeout additionally limits a slow peer.
        val deadline=started+TimeUnit.SECONDS.toNanos(20)
        var stage="LOGIN"
        var resultLabel="FAILED"
        var retained=0
        val outcome=try {
            A0AutoAuthenticatedServer.open(
                app,generation,::stopped,onSocketReady={activeServer=it}
            ).use { session ->
                val server=session.socket
                server.soTimeout=2000
                server.getOutputStream().apply {
                    write(A0PeerRendezvousWire.getPeerAddress(source.peerUsername))
                    flush()
                }
                var address:A0PeerRendezvousWire.ServerEvent.Address?=null
                var ignored=0
                while(address==null && ignored<32 && !stopped() &&
                    System.nanoTime()<deadline) {
                    when(val event=A0PeerRendezvousWire.readServerEvent(
                        server.getInputStream(),source.peerUsername)) {
                        is A0PeerRendezvousWire.ServerEvent.Address -> address=event
                        A0PeerRendezvousWire.ServerEvent.Unrelated -> ignored++
                        else -> throw IOException("Peer folder endpoint unavailable")
                    }
                }
                if(stopped() || address==null)
                    throw IOException("Peer folder address unavailable")
                val endpoint=checkNotNull(address)
                if(System.nanoTime()>=deadline)throw IOException("Folder budget exhausted")
                A0ServerAssociatedTransfer.requireEndpoint(
                    endpoint.address,allowLoopbackForLocalTest=false)
                if(stopped())throw IOException("Peer folder cancelled")
                stage="P_CONNECT"
                Socket().use { peer ->
                    activeP=peer
                    if(!SoulseekActiveSession.register(peer,generation))
                        throw IOException("Peer folder session revoked")
                    try {
                        peer.connect(InetSocketAddress(
                            endpoint.address,endpoint.port),2800)
                        peer.soTimeout=2000
                        peer.getOutputStream().apply {
                            write(A0PeerRendezvousWire.directPeerInit(session.username))
                            write(request)
                            flush()
                        }
                        if(stopped() || System.nanoTime()>=deadline)
                            throw IOException("Peer folder cancelled or expired")
                        stage="REPLY"
                        val files=A0PeerFolderContentsWire.readResponse(
                            peer.getInputStream(),token,folder,
                            deadlineNanos=deadline)
                        if(stopped())throw IOException("Peer folder cancelled")
                        if(files.isEmpty()) {
                            resultLabel="EMPTY"
                            return@use A0AutoDiscoveryCoordinator.SearchResult.NoReply
                        }
                        // The last advertisement is an availability hint, NOT
                        // authenticated truth about a peer's current queue.
                        val speed=source.peerAvailability.speedKiBps
                            ?.takeIf {it in 1..900000}?.times(1024) ?: 0
                        val queue=source.peerAvailability.queueLength ?: 5
                        val available=source.peerAvailability.tier!=
                            A0PeerAvailability.Tier.UNAVAILABLE
                        val claims=A0SoulseekSearchResponse(
                            source.peerUsername,token,files,emptyList(),
                            available,speed,queue)
                        val handoff=A0ObservedCandidateHandoff(
                            server,session.username,token,
                            searchPhrase=slots.first().query,
                            requestedAlbum=slots.first().album,
                            expectedDurationSeconds=slots.first().durationSeconds,
                            saveFormatsOnly=true,qualityProfile=quality,
                            collectReleaseShadow=true,releaseQueries=slots.map {it.query},
                            collectManualCandidates=false
                        )
                        if(!handoff.offerParsed(claims,source.peerUsername))
                            throw IOException("Peer folder evidence rejected")
                        handoff.markReadersStopped()
                        val snapshot=handoff.releaseShadowSnapshot()
                        retained=snapshot.choices.size
                        resultLabel=if(snapshot.truncatedSlots.isNotEmpty())
                            "TRUNCATED" else if(retained>0)"RETAINED" else "EMPTY"
                        if(snapshot.choices.isEmpty() &&
                            snapshot.truncatedSlots.isEmpty())
                            A0AutoDiscoveryCoordinator.SearchResult.NoReply
                        else A0AutoDiscoveryCoordinator.SearchResult.Found(
                            snapshot.choices,snapshot.truncatedSlots)
                    }finally {
                        SoulseekActiveSession.detach(peer)
                        activeP=null
                    }
                }
            }
        }catch(error:Exception){
            resultLabel=when(error) {
                is java.net.SocketTimeoutException -> "TIMEOUT"
                is IOException -> "IO_OR_PROTOCOL"
                else -> "REJECTED"
            }
            A0AutoDiscoveryCoordinator.SearchResult.NoReply
        }finally {
            activeP=null
            activeServer=null
            com.music.bitchord.data.DebugLog.d("BitChord",
                "soulseek_folder_browse stage=$stage result=$resultLabel" +
                    " retained=${retained.coerceIn(0,512)}" +
                    " elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started).coerceIn(0,30000)}")
        }
        return if(stopped())A0AutoDiscoveryCoordinator.SearchResult.Stop
            else outcome
    }

    override fun close() {
        if(!closed.compareAndSet(false,true))return
        runCatching{activeP?.close()}
        runCatching{activeServer?.close()}
    }
}
