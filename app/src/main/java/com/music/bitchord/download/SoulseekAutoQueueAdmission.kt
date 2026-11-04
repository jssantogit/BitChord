package com.music.bitchord.download

import com.music.bitchord.acquisition.a0.A0SearchQuery

/**
 * Admission is deliberately independent from the candidate ranking: neither
 * a download tap nor an enabled preference alone grants unattended execution.
 * This is checked before entering the service queue; the engine checks again
 * immediately before opening any Soulseek socket.
 */
internal object SoulseekAutoQueueAdmission {
    /** Only typed refusal codes may enter telemetry; no queries or source IDs. */
    enum class Refusal {
        REQUEST_NOT_EXPLICIT, OPT_IN_REQUIRED, PILOT_UNAVAILABLE,
        NETWORK_RESTRICTED, BATCH_SIZE_INVALID, DUPLICATE_TRACK_IDS,
        INVALID_TRACK_METADATA,
    }

    fun refusal(
        entries:List<SoulseekBatchPlan.Entry>,
        userRequested:Boolean,
        optedIn:Boolean,
        devPilot:Boolean,
        downloadsAllowed:Boolean,
    ):Refusal? {
        if(!userRequested)return Refusal.REQUEST_NOT_EXPLICIT
        if(!optedIn)return Refusal.OPT_IN_REQUIRED
        if(!devPilot)return Refusal.PILOT_UNAVAILABLE
        if(!downloadsAllowed)return Refusal.NETWORK_RESTRICTED
        if(entries.size !in 1..SoulseekBatchPlan.MAX_TRACKS)
            return Refusal.BATCH_SIZE_INVALID
        if(entries.map { it.trackId }.distinct().size!=entries.size)
            return Refusal.DUPLICATE_TRACK_IDS
        if(!entries.all { entry ->
                entry.trackId.isNotBlank() && entry.trackId.length<=180 &&
                    !entry.trackId.any(Char::isISOControl) &&
                    entry.query.isNotBlank() && entry.query.length<=120 &&
                    !entry.query.any(Char::isISOControl) &&
                    (entry.album==null || (entry.album.length<=120 &&
                        !entry.album.any(Char::isISOControl))) &&
                    (entry.durationSeconds==null ||
                        entry.durationSeconds in 15..7200) &&
                    runCatching { A0SearchQuery.normalize(entry.query) }.isSuccess
            })return Refusal.INVALID_TRACK_METADATA
        return null
    }

    fun allows(
        entries:List<SoulseekBatchPlan.Entry>,
        userRequested:Boolean,
        optedIn:Boolean,
        devPilot:Boolean,
        downloadsAllowed:Boolean,
    ):Boolean = refusal(entries,userRequested,optedIn,devPilot,downloadsAllowed)==null
}
