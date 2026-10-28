package com.music.bitchord.download

import com.music.bitchord.acquisition.a0.A0SearchQuery

/**
 * Admission is deliberately independent from the candidate ranking: neither
 * a download tap nor an enabled preference alone grants unattended execution.
 * This is checked before entering the service queue; the engine checks again
 * immediately before opening any Soulseek socket.
 */
internal object SoulseekAutoQueueAdmission {
    fun allows(
        entries:List<SoulseekBatchPlan.Entry>,
        userRequested:Boolean,
        optedIn:Boolean,
        devPilot:Boolean,
        downloadsAllowed:Boolean,
    ):Boolean {
        if(!userRequested || !optedIn || !devPilot || !downloadsAllowed ||
            entries.size !in 1..SoulseekBatchPlan.MAX_TRACKS)return false
        if(entries.map { it.trackId }.distinct().size!=entries.size)return false
        return entries.all { entry ->
            entry.trackId.isNotBlank() && entry.trackId.length<=180 &&
                !entry.trackId.any(Char::isISOControl) &&
                entry.query.isNotBlank() && entry.query.length<=120 &&
                !entry.query.any(Char::isISOControl) &&
                (entry.album==null || (entry.album.length<=120 &&
                    !entry.album.any(Char::isISOControl))) &&
                (entry.durationSeconds==null || entry.durationSeconds in 15..7200) &&
                runCatching { A0SearchQuery.normalize(entry.query) }.isSuccess
        }
    }
}
