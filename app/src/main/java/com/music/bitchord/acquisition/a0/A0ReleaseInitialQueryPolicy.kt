package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan

/**
 * Order discovery-only public queries for a multi-track release.
 * Never changes the canonical artist/title, eligibility, consent or A3.
 * A release-wide query is reserved only once for each explicitly
 * user-requested batch, with the exact track query available as fallback.
 */
internal object A0ReleaseInitialQueryPolicy {
    // The physical 48-song Proof smoke produced 1024 retained offers,
    // 46 evictions and 26 incomplete slots from ONE album-wide query.
    // Never seed a huge cohort with an unbounded album query: incomplete
    // samples remain review-only even after independent track searches.
    // Short albums still benefit from one shared public search.
    const val MAX_ALBUM_WIDE_COHORT=12
    fun permitsAlbumWide(cohortSize:Int):Boolean =
        cohortSize in 4..MAX_ALBUM_WIDE_COHORT

    /**
     * A queued batch can contain unrelated artists and albums. Only tracks
     * actually sharing the SAME artist + release justify an album-wide
     * initial query; one song inside a mixed 48-item queue is not a cohort.
     */
    fun cohortSize(
        entry:SoulseekBatchPlan.Entry,
        entries:List<SoulseekBatchPlan.Entry>,
    ):Int {
        val album=entry.album?.trim()?.takeIf { it.isNotEmpty() } ?:return 0
        val delimiter=Regex("\\s+[-–—]\\s+")
        val parts=entry.query.split(delimiter,limit=2)
        if(parts.size!=2 || parts[0].isBlank() || parts[1].isBlank())return 0
        val artist=parts[0].trim()
        return entries.count { sibling ->
            val other=sibling.query.split(delimiter,limit=2)
            other.size==2 && other[1].isNotBlank() &&
                other[0].trim().equals(artist,ignoreCase=true) &&
                sibling.album?.trim()?.equals(album,ignoreCase=true)==true
        }
    }

    enum class Kind { TRACK, ALBUM_WIDE }
    data class Selection(val query:String,val kind:Kind)

    fun choose(
        canonicalQuery:String,
        releaseWideQuery:String?,
        releaseSlots:Int,
        sentReleaseQueries:Set<String>,
    ):Selection {
        require(canonicalQuery.isNotBlank() && releaseSlots>0)
        val album=releaseWideQuery?.takeIf {
            it.length>=3 && it!=canonicalQuery &&
                permitsAlbumWide(releaseSlots) && it !in sentReleaseQueries
        }
        return if(album!=null)Selection(album,Kind.ALBUM_WIDE)
            else Selection(canonicalQuery,Kind.TRACK)
    }
}
