package com.music.bitchord.acquisition.a0

/**
 * Bounded within a SINGLE user-initiated Soulseek discovery session.
 * Keeps canonical artist/title for screening; variants only change wire text.
 * Maximum two fallback requests, no reconnect/login/retry loop or transfer.
 */
internal class A0SearchRecoveryPlan(original:String) {
    private val variants=A0SearchQuery.variants(original).drop(1)
        .filter { it.length>=3 }.distinct().take(2)
    val authorizesTransfer:Boolean get()=false

    fun next(
        elapsedMillis:Long,
        hasRelevantCandidate:Boolean,
        alreadySent:Set<String>,
    ):String? {
        if(elapsedMillis<6000 || elapsedMillis>15000 ||
            hasRelevantCandidate || alreadySent.size>=3)return null
        return variants.firstOrNull { it !in alreadySent }
    }

    override fun toString()="A0SearchRecoveryPlan(redacted)"
}
