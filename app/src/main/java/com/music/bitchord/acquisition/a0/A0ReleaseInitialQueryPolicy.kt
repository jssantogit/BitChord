package com.music.bitchord.acquisition.a0

/**
 * Order discovery-only public queries for a multi-track release.
 * Never changes the canonical artist/title, eligibility, consent or A3.
 * A release-wide query is reserved only once for each explicitly
 * user-requested batch, with the exact track query available as fallback.
 */
internal object A0ReleaseInitialQueryPolicy {
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
                releaseSlots>=4 && it !in sentReleaseQueries
        }
        return if(album!=null)Selection(album,Kind.ALBUM_WIDE)
            else Selection(canonicalQuery,Kind.TRACK)
    }
}
