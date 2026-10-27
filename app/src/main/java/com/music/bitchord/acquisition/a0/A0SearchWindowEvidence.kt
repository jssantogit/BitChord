package com.music.bitchord.acquisition.a0

/**
 * Shadow-only snapshots taken at the search window boundaries and after
 * worker teardown. A value observed after drainage must never be reported
 * as though it arrived by second 18. No transfer or early-stop authority.
 */
internal class A0SearchWindowEvidence {
    internal data class Sample(val replies:Int,val claims:Int) {
        init { require(replies>=0 && claims>=0) }
    }
    internal data class Summary(
        val hasBothCheckpoints:Boolean,
        val hasWindowEndCheckpoint:Boolean,
        val at6:Sample,
        val at12:Sample,
        val at18:Sample,
        val afterDrain:Sample,
        val netRepliesAfter12:Int,
        val netClaimsAfter12:Int,
        val netClaims6To12:Int,
        val repliesDuringDrain:Int,
        val claimsDuringDrain:Int,
    ) {
        val checkpointsComplete:Boolean
            get()=hasBothCheckpoints && hasWindowEndCheckpoint
    }

    private var at6:Sample?=null
    private var at12:Sample?=null
    private var at18:Sample?=null

    fun record(second:Int,replies:Int,claims:Int) {
        require(second in setOf(6,12,18))
        val sample=Sample(replies.coerceIn(0,100000),claims.coerceIn(0,100000))
        when(second) {
            6 -> at6=sample
            12 -> at12=sample
            else -> at18=sample
        }
    }

    fun finish(replies:Int,claims:Int):Summary {
        val drained=Sample(replies.coerceIn(0,100000),claims.coerceIn(0,100000))
        val first=at6
        val middle=at12
        val end=at18
        return Summary(
            hasBothCheckpoints=first!=null && middle!=null,
            hasWindowEndCheckpoint=end!=null,
            at6=first ?: Sample(0,0),
            at12=middle ?: Sample(0,0),
            at18=end ?: Sample(0,0),
            afterDrain=drained,
            netRepliesAfter12=if(middle!=null && end!=null)end.replies-middle.replies else 0,
            netClaimsAfter12=if(middle!=null && end!=null)end.claims-middle.claims else 0,
            netClaims6To12=if(first!=null && middle!=null)middle.claims-first.claims else 0,
            repliesDuringDrain=if(end!=null)drained.replies-end.replies else 0,
            claimsDuringDrain=if(end!=null)drained.claims-end.claims else 0,
        )
    }
}
