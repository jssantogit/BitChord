package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.LISTENBRAINZ_PUBLIC_TTL_MS
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRange
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording

/** Current public week-window chart; never mixed with personal listening. */
internal data class CanonicalSitewideDiscoveryState(
    val range: ListenBrainzSitewideRange = ListenBrainzSitewideRange.WEEK,
    val recordings: List<ListenBrainzSitewideRecording> = emptyList(),
    val fetchedAtMs: Long? = null,
    val upstreamUpdatedAtSeconds: Long? = null,
)

/** Do not present expired community windows as new weekly trends. */
internal fun canonicalSitewideDiscoveryIsFresh(
    state: CanonicalSitewideDiscoveryState,
    nowMs: Long,
): Boolean {
    val fetched = state.fetchedAtMs ?: return false
    return state.range == ListenBrainzSitewideRange.WEEK &&
        nowMs >= fetched && nowMs - fetched < LISTENBRAINZ_PUBLIC_TTL_MS
}
