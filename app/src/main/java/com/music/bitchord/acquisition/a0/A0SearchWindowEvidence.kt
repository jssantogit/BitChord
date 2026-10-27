package com.music.bitchord.acquisition.a0

/**
 * Shadow-only discovery timing curve for #177. Never closes the search,
 * approves a candidate or authorizes a transfer. Only aggregate counts
 * (not track, peer or filename identifiers) may be exported.
 *
 * Net candidate counts are not recall: candidates may be displaced by
 * higher-ranked offers, and a plateau does NOT prove no valuable late peers.
 */
internal class A0SearchWindowEvidence {
    internal data class Sample(val replies:Int,val claims:Int) {
        init { require(replies>=0 && claims>=0) }
    }
    internal data class Summary(
        val hasBothCheckpoints:Boolean,
        val at6:Sample,
        val at12:Sample,
        val at18:Sample,
        val netRepliesAfter12:Int,
        val netClaimsAfter12:Int,
        val netClaims6To12:Int,
    )

    private var at6:Sample?=null
    private var at12:Sample?=null

    fun record(second:Int,replies:Int,claims:Int) {
        require(second==6 || second==12)
        val sample=Sample(replies.coerceIn(0,100000),claims.coerceIn(0,100000))
        if(second==6)at6=sample else at12=sample
    }

    fun finish(replies:Int,claims:Int):Summary {
        val last=Sample(replies.coerceIn(0,100000),claims.coerceIn(0,100000))
        val first=at6
        val middle=at12
        return Summary(
            hasBothCheckpoints=first!=null && middle!=null,
            at6=first ?: Sample(0,0),
            at12=middle ?: Sample(0,0),
            at18=last,
            netRepliesAfter12=if(middle!=null)last.replies-middle.replies else 0,
            netClaimsAfter12=if(middle!=null)last.claims-middle.claims else 0,
            netClaims6To12=if(first!=null && middle!=null)middle.claims-first.claims else 0,
        )
    }
}
