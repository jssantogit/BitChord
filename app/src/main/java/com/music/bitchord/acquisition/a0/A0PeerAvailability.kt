package com.music.bitchord.acquisition.a0

/**
 * Source availability is an independent, best-effort signal from untrusted Soulseek peers.
 * It MUST NOT affect identity, declared audio quality, preflight confidence or transfer
 * authorization. Upload speed is an advertised bytes/second value, not a measured ETA.
 */
internal object A0PeerAvailability {
    enum class Tier { READY, UNKNOWN, CONGESTED, UNAVAILABLE }
    enum class Reason {
        NO_FREE_SLOT, FILE_LOCKED, QUEUE_CONGESTED, SPEED_SLOW,
        QUEUE_UNKNOWN, SPEED_UNKNOWN
    }
    data class Assessment(
        val tier:Tier,
        val reasons:List<Reason>,
        val queueLength:Int?,
        val speedKiBps:Int?,
    )

    fun assess(
        hasFreeUploadSlot:Boolean,
        locked:Boolean,
        queueLength:Int?,
        uploadSpeedBytesPerSecond:Int?,
    ):Assessment {
        val queue=queueLength?.takeIf { it in 0..1_000_000 }
        val speed=uploadSpeedBytesPerSecond?.takeIf { it in 1..1_000_000_000 }
        val reasons=mutableListOf<Reason>()
        if(!hasFreeUploadSlot) reasons.add(Reason.NO_FREE_SLOT)
        if(locked) reasons.add(Reason.FILE_LOCKED)
        if(queue==null) reasons.add(Reason.QUEUE_UNKNOWN)
        else if(queue>=25) reasons.add(Reason.QUEUE_CONGESTED)
        if(speed==null) reasons.add(Reason.SPEED_UNKNOWN)
        else if(speed<=16*1024) reasons.add(Reason.SPEED_SLOW)
        val tier=when {
            !hasFreeUploadSlot || locked -> Tier.UNAVAILABLE
            Reason.QUEUE_CONGESTED in reasons || Reason.SPEED_SLOW in reasons ->
                Tier.CONGESTED
            queue!=null && queue<=2 && speed!=null && speed>=64*1024 ->
                Tier.READY
            else -> Tier.UNKNOWN
        }
        return Assessment(tier,reasons,queue,speed?.div(1024))
    }

    /**
     * A positive result means the first source looks more favorable.
     * Apply ONLY after identical claim and identical preflight assessment:
     * these are untrusted observations, never an ETA or transfer approval.
     */
    fun compareSource(a:Assessment,b:Assessment):Int {
        val tier=priority(a).compareTo(priority(b))
        if(tier!=0)return tier
        // Prefer known shorter queues before unverified speed. Unknown
        // metrics sort last within their tier, never invalidate the source.
        val queue=(b.queueLength ?: Int.MAX_VALUE)
            .compareTo(a.queueLength ?: Int.MAX_VALUE)
        if(queue!=0)return queue
        return (a.speedKiBps ?: -1).compareTo(b.speedKiBps ?: -1)
    }

    /** Only a tie-breaker after the identity/quality rank. */
    fun priority(a:Assessment)=when(a.tier) {
        Tier.READY -> 3
        Tier.UNKNOWN -> 2
        Tier.CONGESTED -> 1
        Tier.UNAVAILABLE -> 0
    }
}
