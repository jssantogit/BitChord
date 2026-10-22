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
    allowLoopbackForLocalTest:Boolean=false
) {
    data class Choice internal constructor(
        val displayName:String,
        val sizeBytes:Long,
        internal val peerUsername:String,
        internal val remotePath:String,
    ) {
        override fun toString()="A0 observed candidate (redacted)"
    }

    private val transport=A0ServerAssociatedTransfer(allowLoopbackForLocalTest)
    private val candidates=ArrayList<Choice>(12)
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
            result.peerUsername!=claimedPeerUsername||!result.hasFreeUploadSlot)return
        try { A0PeerRendezvousWire.getPeerAddress(result.peerUsername) }
        catch (_:IllegalArgumentException) { return }

        val validExtensions=setOf("mp3","flac","wav","ogg","m4a","aac","opus")
        val locked=result.lockedFiles.map { it.remoteFilename }.toHashSet()
        for(file in result.files) {
            if(candidates.size>=12)break
            val name=file.remoteFilename
            val ext=file.extension.lowercase().removePrefix(".")
            if(ext !in validExtensions || name in locked ||
                file.sizeBytes !in 44L..16L*1024L*1024L ||
                !A0ModernIncomingTransfer.isSafeRemoteName(name) ||
                candidates.any { it.peerUsername==result.peerUsername &&
                    it.remotePath==name }) continue
            val display=name.replace('\\','/').substringAfterLast('/')
                .take(82)
            candidates.add(Choice(
                displayName=display, sizeBytes=file.sizeBytes,
                peerUsername=result.peerUsername,remotePath=name
            ))
        }
    }

    @Synchronized
    fun choices():List<Choice> = if(cancelled||spent)emptyList() else candidates.toList()

    @Synchronized
    fun hasCandidates()=candidates.isNotEmpty()&&!cancelled&&!spent

    @Synchronized
    fun markReadersStopped() {
        check(!spent&&!cancelled)
        readersStopped=true
    }

    fun transferChoice(index:Int,privateDirectory:File):A0VerifiedByteStager.Receipt {
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
            ),privateDirectory
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
