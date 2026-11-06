package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.LISTENBRAINZ_PUBLIC_TTL_MS
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRange
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording
import com.music.bitchord.data.catalog.metadata.listenBrainzSitewideSnapshotIsCurrent
import com.music.bitchord.data.catalog.metadata.RecordingMetadataEnrichment
import com.music.bitchord.data.catalog.metadata.canonicalStreamingTitleIsSafe
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import java.util.Locale

/** Current public week-window chart; never mixed with personal listening. */
internal data class CanonicalSitewideDiscoveryState(
    val range: ListenBrainzSitewideRange = ListenBrainzSitewideRange.WEEK,
    val recordings: List<ListenBrainzSitewideRecording> = emptyList(),
    val verifiedRows: List<CanonicalCatalogSearchRow> = emptyList(),
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
        nowMs >= fetched && nowMs - fetched < LISTENBRAINZ_PUBLIC_TTL_MS &&
        listenBrainzSitewideSnapshotIsCurrent(
            state.range, state.upstreamUpdatedAtSeconds, nowMs,
        )
}

/**
 * Exact point at which a weekly badge must be reconsidered on a long-lived
 * Explore screen. A Compose remember block must not freeze System.currentTimeMillis()
 * at initial composition and continue showing an expired chart indefinitely.
 * The caller ticks after this deadline and can request a fresh snapshot.
 */
internal fun canonicalSitewideDiscoveryExpiresAtMs(
    state: CanonicalSitewideDiscoveryState,
): Long? {
    if (state.range != ListenBrainzSitewideRange.WEEK) return null
    val ttl = LISTENBRAINZ_PUBLIC_TTL_MS
    val maxUpstreamAge = 14L * 24L * 60L * 60L * 1_000L
    val fetched = state.fetchedAtMs
        ?.takeIf { it >= 0L && it <= Long.MAX_VALUE - ttl } ?: return null
    val updatedSeconds = state.upstreamUpdatedAtSeconds
        ?.takeIf { it > 0L && it <= (Long.MAX_VALUE - maxUpstreamAge) / 1_000L }
        ?: return null
    return minOf(fetched + ttl, updatedSeconds * 1_000L + maxUpstreamAge)
}

/**
 * Public sitewide counts supply only a position and recording MBID.
 * Titles, credits and durations come from a direct MusicBrainz Recording
 * lookup. A metadata-only chart row MUST NOT imply a playable asset.
 */
internal fun canonicalVerifiedSitewideRows(
    rankings: List<ListenBrainzSitewideRecording>,
    musicBrainzMetadata: Map<String, RecordingMetadataEnrichment>,
    limit: Int = 12,
): List<CanonicalCatalogSearchRow> {
    if (limit <= 0) return emptyList()
    val verifiedById = musicBrainzMetadata.entries.associate {
        it.key.lowercase(Locale.ROOT) to it.value
    }
    val validMbid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    return rankings.asSequence()
        .filter { it.listenCount > 0L }
        .sortedWith(compareByDescending<ListenBrainzSitewideRecording> { it.listenCount }
            .thenBy { it.recordingMbid })
        .distinctBy { it.recordingMbid.lowercase(Locale.ROOT) }
        .mapNotNull { ranking ->
            val id = ranking.recordingMbid.lowercase(Locale.ROOT)
            if (!validMbid.matches(id)) return@mapNotNull null
            val metadata = verifiedById[id] ?: return@mapNotNull null
            if (!metadata.musicBrainzId.equals(id, ignoreCase = true) ||
                metadata.artistCredits.orEmpty().none {
                    it.musicBrainzArtistId?.lowercase(Locale.ROOT)
                        ?.let(validMbid::matches) == true
                }
            ) return@mapNotNull null
            val title = metadata.title?.trim()
                ?.takeIf { canonicalStreamingTitleIsSafe(it) } ?: return@mapNotNull null
            val artist = metadata.artistDisplay?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            CanonicalCatalogSearchRow(
                musicBrainzId = id,
                kind = CanonicalSearchKind.RECORDING,
                title = title,
                subtitle = artist,
                durationMs = metadata.durationMs?.takeIf { it > 0L },
            )
        }.take(limit.coerceAtMost(12)).toList()
}

/** A bounded batch of exact public MBIDs, excluding already catalogued rows.
 * Ranking remains public; no title, cover or audio is inferred from counts. */
internal fun canonicalSitewideMissingRecordingIds(
    rankings: List<ListenBrainzSitewideRecording>,
    knownRecordingMbids: Set<String>,
    limit: Int = 16,
): List<String> = rankings.asSequence()
    .filter { it.listenCount > 0L }
    .map { it.recordingMbid.lowercase(Locale.ROOT) }
    .filter { it !in knownRecordingMbids }
    .distinct()
    .take(limit.coerceIn(0, 25))
    .toList()
