package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.IOException
import java.net.Socket

/**
 * Manual devDebug selection from untrusted search results.
 * No automatic transfer and no claims about copyright or authentic source hash.
 *
 * A result is displayed to the user only, never logged/serialized. The sole
 * authenticated Soulseek server socket passes to P/F AFTER search readers end.
 */
internal class A0ObservedCandidateHandoff(
    private val authenticatedServer:Socket,
    private val localUsername:String,
    private val searchToken:Int,
    allowLoopbackForLocalTest:Boolean=false,
    private val searchPhrase:String?=null
) {
    class Choice internal constructor(
        val displayName:String,
        val sizeBytes:Long,
        internal val peerUsername:String,
        internal val remotePath:String,
        val matchScore:Int,
        val matchSignal:A0CandidateRank.Match,
        val declaredBitrateKbps:Int?,
    ) {
        override fun toString()="A0 observed candidate (redacted)"
    }

    /** Only sanitized counters. No usernames, search strings or paths. */
    data class Screening(
        val responses:Int,
        val publicFiles:Int,
        val lockedFiles:Int,
        val tooLarge:Int,
        val unsupportedFormat:Int,
        val unsafePath:Int,
        val noSlot:Int,
        val accepted:Int,
        val validBeforeLimit:Int=0,
        val duplicates:Int=0,
        val capacityDropped:Int=0,
    )
    private val transport=A0ServerAssociatedTransfer(allowLoopbackForLocalTest)
    private val candidates=ArrayList<Choice>(48)
    private val choiceOrder = compareByDescending<Choice> { it.matchScore }
        .thenByDescending { it.declaredBitrateKbps ?: 0 }
        .thenBy { it.displayName.lowercase(java.util.Locale.ROOT) }
        .thenBy { it.peerUsername }
        .thenBy { it.remotePath }
    private var eligible=0
    private var duplicateCount=0
    private var capDropped=0
    private var replies=0
    private var publicSeen=0
    private var lockedSeen=0
    private var oversized=0
    private var unsupported=0
    private var unsafe=0
    private var noSlotSeen=0
    private var readersStopped=false
    private var cancelled=false
    private var spent=false

    init {require(searchToken>0)}

    fun offerSearchResult(frame:ByteArray,claimedPeerUsername:String) {
        val result=try {
            A0SoulseekSearchResponseParser.parse(frame,searchToken)
        } catch (_:IOException) {return} catch (_:IllegalArgumentException) {return}
        offerParsed(result,claimedPeerUsername)
    }

    @Synchronized
    fun offerParsed(result:A0SoulseekSearchResponse,claimedPeerUsername:String) {
        if(cancelled||spent||readersStopped||result.token!=searchToken ||
            result.peerUsername!=claimedPeerUsername) return
        try { A0PeerRendezvousWire.getPeerAddress(result.peerUsername) }
        catch (_:IllegalArgumentException) { return }
        replies=(replies+1).coerceAtMost(100000)
        publicSeen=(publicSeen+result.files.size).coerceAtMost(100000)
        lockedSeen=(lockedSeen+result.lockedFiles.size).coerceAtMost(100000)

        val validExtensions=setOf("mp3","flac","wav","ogg","m4a","aac","opus")
        val locked=result.lockedFiles.map{it.remoteFilename}.toHashSet()
        for(file in result.files) {
            val path=file.remoteFilename
            // Reject unsafe metadata before any UI or disk handoff. The full
            // untrusted remote path is never exported through diagnostics.
            if(!A0ModernIncomingTransfer.isSafeRemoteName(path)) {
                unsafe=(unsafe+1).coerceAtMost(100000)
                continue
            }
            val basename=path.replace('\\','/').substringAfterLast('/')
            val ext=basename.substringAfterLast('.',missingDelimiterValue="").lowercase()
            val advertised=file.extension.lowercase().removePrefix(".")
            if(ext !in validExtensions ||
                (advertised.isNotEmpty() && advertised!=ext)) {
                unsupported=(unsupported+1).coerceAtMost(100000)
                continue
            }
            if(file.sizeBytes !in 44L..64L*1024L*1024L) {
                oversized=(oversized+1).coerceAtMost(100000)
                continue
            }
            if(!result.hasFreeUploadSlot || path in locked) {
                noSlotSeen=(noSlotSeen+1).coerceAtMost(100000)
                continue
            }
            if(candidates.any {
                it.peerUsername==result.peerUsername && it.remotePath==path
            }) {
                duplicateCount=(duplicateCount+1).coerceAtMost(100000)
                continue
            }
            eligible=(eligible+1).coerceAtMost(100000)
            val bitrate=file.attributes[0]?.takeIf { it in 1..10000 }
            val ranking=A0CandidateRank.rank(searchPhrase,basename,bitrate)
            candidates.add(Choice(
                displayName=basename.take(82),sizeBytes=file.sizeBytes,
                peerUsername=result.peerUsername,remotePath=path,
                matchScore=ranking.score, matchSignal=ranking.match,
                declaredBitrateKbps=bitrate
            ))
            candidates.sortWith(choiceOrder)
            if(candidates.size>48) {
                candidates.removeAt(candidates.lastIndex)
                capDropped=(capDropped+1).coerceAtMost(100000)
            }
        }
    }

    @Synchronized
    fun screening():Screening=Screening(
        responses=replies,
        publicFiles=publicSeen,
        lockedFiles=lockedSeen,
        tooLarge=oversized,
        unsupportedFormat=unsupported,
        unsafePath=unsafe,
        noSlot=noSlotSeen,
        accepted=candidates.size,
        validBeforeLimit=eligible,
        duplicates=duplicateCount,
        capacityDropped=capDropped
    )

    @Synchronized
    fun choices():List<Choice> = if(cancelled||spent)emptyList() else candidates.toList()

    @Synchronized
    fun hasCandidates()=candidates.isNotEmpty()&&!cancelled&&!spent

    @Synchronized
    fun markReadersStopped() {
        check(!spent&&!cancelled)
        readersStopped=true
    }

    fun transferChoice(
        index:Int,
        privateDirectory:File,
        onPhase:(A0ServerAssociatedTransfer.Phase)->Unit={}
    ):A0VerifiedByteStager.Receipt {
        val selected:Choice
        synchronized(this){
            if(cancelled)throw IOException("A0 transfer cancelled")
            check(readersStopped) { "A0 server reader must stop before P/F" }
            check(!spent) { "A0 manual transfer already attempted" }
            require(index in candidates.indices) { "Invalid selected file" }
            selected=candidates[index]
            spent=true
            candidates.clear()
        }
        return transport.runObservedOnce(
            authenticatedServer,localUsername,selected.peerUsername,
            A0OpenAudioCandidateGate.PublicPeerListing(
                selected.remotePath,selected.sizeBytes,locked=false,freeSlot=true
            ),privateDirectory,onPhase
        )
    }

    @Synchronized
    fun cancel(){
        cancelled=true
        candidates.clear()
        transport.cancel()
    }

    override fun toString()="A0ObservedCandidateHandoff(redacted)"
}
