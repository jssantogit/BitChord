package com.music.bitchord.download

/**
 * One album/EP/single release request becomes an ordered list of separate
 * manual authorizations. There is no automatic selection, implicit retry,
 * parallel P/F session or filename-based identity assumption.
 */
internal object SoulseekBatchPlan {
    const val MAX_TRACKS = 64

    data class SourceTrack(
        val trackId: String,
        val artist: String,
        val title: String,
        val creditedArtists: List<String>,
        val album: String?,
        val durationSeconds: Int?,
    )

    data class Entry(
        val trackId: String,
        val query: String,
        val album: String?,
        val durationSeconds: Int?,
    )

    fun prepare(
        tracks: List<SourceTrack>,
        releaseTitle: String?,
        isRelease: Boolean,
        pilotAvailable: Boolean,
    ): List<Entry>? {
        if(!isRelease || !pilotAvailable || tracks.size !in 2..MAX_TRACKS) return null
        val entries=tracks.map { song ->
            if(song.trackId.isBlank() || song.trackId.length>180) return null
            val routed=SoulseekDownloadRouting.route(
                artist=song.artist,title=song.title,count=1,
                pilotAvailable=true,creditedArtists=song.creditedArtists,
            )
            if(routed !is SoulseekDownloadRouting.Decision.ManualSearch) return null
            val album=SoulseekDownloadRouting.albumHint(
                songAlbum=song.album,playingAlbum=null,targetAlbum=releaseTitle,
                samePlayingTrack=false,fromAlbum=true,
            )
            Entry(
                trackId=song.trackId,
                query=routed.query,
                album=album,
                durationSeconds=song.durationSeconds?.takeIf{it in 15..7200},
            )
        }
        return entries
    }
}

/** In-memory, per-Activity batch state. Each saved item gets its own ledger
 * record from the existing acquisition pipeline. Nothing is marked saved until
 * MediaStore/SAF publication succeeds; skipping never deletes an existing file.
 */
internal class SoulseekBatchSession(val entries: List<SoulseekBatchPlan.Entry>) {
    init { require(entries.size in 2..SoulseekBatchPlan.MAX_TRACKS) }
    var index:Int=0
        private set
    var succeeded:Int=0
        private set
    var skipped:Int=0
        private set
    private var savedCurrent=false

    val current:SoulseekBatchPlan.Entry?
        get()=entries.getOrNull(index)
    val isComplete:Boolean get()=index>=entries.size

    fun markSaved():Boolean {
        if(isComplete || savedCurrent)return false
        savedCurrent=true
        succeeded++
        return true
    }
    /** The user moves on intentionally; every failed/unselected track counts
     * as skipped and does not affect the next item's authorization. */
    fun advance():Boolean {
        if(isComplete)return false
        if(!savedCurrent)skipped++
        index++
        savedCurrent=false
        return !isComplete
    }
}
