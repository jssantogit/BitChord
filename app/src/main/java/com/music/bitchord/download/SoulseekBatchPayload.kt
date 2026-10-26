package com.music.bitchord.download

import com.music.bitchord.acquisition.a0.A0SearchQuery

/** Only bounded presentation metadata crosses the explicit internal Intent;
 * never credentials, remote peer paths, tokens or transfer authorization.
 * The receiving activity refuses malformed parallel arrays atomically.
 */
internal object SoulseekBatchPayload {
    fun decode(
        queries:List<String>?, albums:List<String>?, durations:IntArray?,
    ):List<SoulseekBatchPlan.Entry>? {
        if(queries==null || albums==null || durations==null ||
            queries.size !in 2..SoulseekBatchPlan.MAX_TRACKS ||
            albums.size!=queries.size || durations.size!=queries.size) return null
        return queries.indices.map { index ->
            val q=queries[index]
            val album=albums[index]
            if(q.isBlank() || q.length>120 || album.length>120 ||
                album.any { it.isISOControl() })return null
            if(runCatching { A0SearchQuery.normalize(q) }.isFailure)return null
            val duration=durations[index]
            if(duration!=0 && duration !in 15..7200)return null
            SoulseekBatchPlan.Entry(
                trackId=(index+1).toString(), query=q,
                album=album.takeIf(String::isNotBlank),
                durationSeconds=duration.takeIf { it>0 },
            )
        }
    }
}
