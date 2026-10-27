package com.music.bitchord.acquisition.a0

/**
 * Anonymous, bounded *per-search* observations of peer transport behavior.
 * No network, file I/O, persistence, tracking or transfer authority.
 *
 * A server-advertised upload slot/speed is a weaker signal than a successful
 * P reply, but neither is evidence of the recording's identity or audio bytes.
 */
internal class A0PeerReliabilityEvidence {
    enum class Outcome { REPLY_VALID, CONNECT_FAILED, READ_TIMEOUT, INVALID_REPLY, CANCELLED }

    class Alternative(
        val peerKey:String,
        val claimKey:String,
        val availability:A0PeerAvailability.Tier,
    ) {
        override fun toString()="Alternative(redacted)"
    }

    private data class Evidence(var observations:Int=0, var score:Int=0)
    private val peers=LinkedHashMap<String,Evidence>()

    val grantsTransfer:Boolean get()=false
    val peerCount:Int get()=peers.size
    val totalEvents:Int get()=peers.values.sumOf { it.observations }

    @Synchronized fun observe(peerKey:String,outcome:Outcome) {
        // A cancellation is user/system-caused, not evidence of peer failure.
        if(outcome==Outcome.CANCELLED || peerKey.isBlank() || peerKey.length>256)return
        val value=peers[peerKey] ?: run {
            if(peers.size>=MAX_PEERS)peers.remove(peers.keys.first())
            Evidence().also { peers[peerKey]=it }
        }
        if(value.observations>=MAX_EVENTS_PER_PEER)return
        value.observations++
        value.score=(value.score+when(outcome) {
            Outcome.REPLY_VALID -> 4
            Outcome.CONNECT_FAILED -> -3
            Outcome.READ_TIMEOUT -> -2
            Outcome.INVALID_REPLY -> -4
            Outcome.CANCELLED -> 0
        }).coerceIn(-MAX_EVENTS_PER_PEER*4,MAX_EVENTS_PER_PEER*4)
    }

    @Synchronized fun score(peerKey:String):Int=peers[peerKey]?.score ?: 0
    @Synchronized fun observedCount(peerKey:String):Int=peers[peerKey]?.observations ?: 0

    /**
     * Read-only sequential fallback order for metadata-EQUIVALENT claims.
     * No parallel jobs, protocol retry, permission, or safety authorization.
     * Source reliability breaks a tie only AFTER a caller has established
     * equivalent musical/quality preflight evidence.
     */
    @Synchronized fun sequentialFallbacks(
        alternatives:List<Alternative>,
        claimKey:String,
        failedPeers:Set<String>,
        maxPeers:Int=3,
    ):List<String> {
        require(maxPeers in 1..3)
        if(claimKey.isBlank())return emptyList()
        return alternatives.withIndex()
            .filter { it.value.claimKey==claimKey &&
                it.value.peerKey !in failedPeers && it.value.peerKey.isNotBlank() }
            .distinctBy { it.value.peerKey }
            .sortedWith(compareByDescending<IndexedValue<Alternative>> {
                score(it.value.peerKey)
            }.thenByDescending {
                when(it.value.availability) {
                    A0PeerAvailability.Tier.READY -> 3
                    A0PeerAvailability.Tier.UNKNOWN -> 2
                    A0PeerAvailability.Tier.CONGESTED -> 1
                    A0PeerAvailability.Tier.UNAVAILABLE -> 0
                }
            }.thenBy { it.index })
            .take(maxPeers).map { it.value.peerKey }
    }

    override fun toString()="A0PeerReliabilityEvidence(redacted)"

    companion object {
        const val MAX_PEERS=256
        const val MAX_EVENTS_PER_PEER=16
    }
}
