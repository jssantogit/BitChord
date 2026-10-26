package com.music.bitchord.acquisition.a0

import java.text.Normalizer
import java.util.Locale

/**
 * In-memory, activity-local comparison of two searches. Only typed outcome and
 * bounded counts are emitted to diagnostics; NEVER queries, albums, usernames,
 * paths, credentials, hashes or an unbounded history of past searches.
 */
internal class A0SearchComparison {
    enum class Kind { FIRST, CHANGED, REPEAT_GAINED, REPEAT_LOST, REPEAT_STABLE }

    class Observation(
        val kind:Kind,
        val previousCandidates:Int,
        val currentCandidates:Int,
        val previousPeerReplies:Int,
        val currentPeerReplies:Int,
    ) {
        override fun toString()="SearchComparison(${kind.name},counts-only)"
    }

    private data class Signature(
        val query:String,val album:String,val preset:String
    )
    private var signature:Signature?=null
    private var candidates=0
    private var peerReplies=0

    private fun normalized(value:String?):String=Normalizer.normalize(
        value.orEmpty().trim().replace(Regex("\\s+")," "),Normalizer.Form.NFKC
    ).lowercase(Locale.ROOT)

    @Synchronized fun observe(
        query:String,album:String?,qualityPreset:String,
        candidateCount:Int,validatedPeerReplies:Int,
    ):Observation {
        val next=Signature(normalized(query),normalized(album),qualityPreset)
        val previous=signature
        val wasSame=previous!=null && next==previous
        val priorCandidates=if(wasSame)candidates else 0
        val priorReplies=if(wasSame)peerReplies else 0
        val count=candidateCount.coerceIn(0,100000)
        val replies=validatedPeerReplies.coerceIn(0,100000)
        val kind=when {
            previous==null -> Kind.FIRST
            !wasSame -> Kind.CHANGED
            priorCandidates==0 && count>0 -> Kind.REPEAT_GAINED
            priorCandidates>0 && count==0 -> Kind.REPEAT_LOST
            else -> Kind.REPEAT_STABLE
        }
        signature=next
        candidates=count
        peerReplies=replies
        return Observation(kind,priorCandidates,count,priorReplies,replies)
    }

    override fun toString()="A0SearchComparison(redacted)"
}
