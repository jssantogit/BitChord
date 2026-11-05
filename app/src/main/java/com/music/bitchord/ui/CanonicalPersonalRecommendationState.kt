package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ListenBrainzRecordingRecommendation
import com.music.bitchord.data.catalog.metadata.RecordingMetadataEnrichment
import com.music.bitchord.data.catalog.metadata.canonicalStreamingTitleIsSafe
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import java.util.Locale

private const val PERSONAL_FETCH_TTL_MS = 60L * 60L * 1_000L
private const val PERSONAL_UPSTREAM_TTL_MS = 30L * 24L * 60L * 60L * 1_000L

/**
 * Personal recommendations are private MusicBrainz identity candidates. The
 * label "For you" is only used if a current authorized ListenBrainz model and
 * independently verified MusicBrainz Recording metadata are both available.
 */
internal data class CanonicalPersonalRecommendationState(
    val recordings: List<ListenBrainzRecordingRecommendation> = emptyList(),
    val verifiedRows: List<CanonicalCatalogSearchRow> = emptyList(),
    val fetchedAtMs: Long? = null,
    val upstreamUpdatedSeconds: Long? = null,
)

internal fun canonicalPersonalRecommendationExpiresAtMs(
    state: CanonicalPersonalRecommendationState,
): Long? {
    val fetched = state.fetchedAtMs?.takeIf { it > 0L &&
        it <= Long.MAX_VALUE - PERSONAL_FETCH_TTL_MS } ?: return null
    val sourceTime = state.upstreamUpdatedSeconds?.takeIf { it > 0L &&
        it <= (Long.MAX_VALUE - PERSONAL_UPSTREAM_TTL_MS) / 1_000L }
        ?: return null
    return minOf(fetched + PERSONAL_FETCH_TTL_MS,
        sourceTime * 1_000L + PERSONAL_UPSTREAM_TTL_MS)
}

internal fun canonicalPersonalRecommendationIsFresh(
    state: CanonicalPersonalRecommendationState,
    nowMs: Long,
): Boolean {
    val expires = canonicalPersonalRecommendationExpiresAtMs(state) ?: return false
    val fetched = state.fetchedAtMs ?: return false
    return nowMs >= fetched && nowMs < expires
}

/**
 * An MBID and score alone never furnish a Song or an album. Verify the exact
 * MusicBrainz Recording and at least one typed Artist identity; reject unsafe
 * titles and output metadata-only rows with no audio or inferred artwork.
 */
internal fun canonicalVerifiedPersonalRecommendationRows(
    candidates: List<ListenBrainzRecordingRecommendation>,
    musicBrainzMetadata: Map<String, RecordingMetadataEnrichment>,
    limit: Int = 12,
): List<CanonicalCatalogSearchRow> {
    if (limit <= 0) return emptyList()
    val validMbid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    val known = musicBrainzMetadata.mapKeys { it.key.lowercase(Locale.ROOT) }
    return candidates.asSequence()
        .filter { it.score.isFinite() && it.score > 0.0 }
        .sortedWith(compareByDescending<ListenBrainzRecordingRecommendation> { it.score }
            .thenBy { it.recordingMbid })
        .distinctBy { it.recordingMbid.lowercase(Locale.ROOT) }
        .mapNotNull { candidate ->
            val id = candidate.recordingMbid.lowercase(Locale.ROOT)
            if (!validMbid.matches(id)) return@mapNotNull null
            val metadata = known[id] ?: return@mapNotNull null
            if (!metadata.musicBrainzId.equals(id, ignoreCase = true) ||
                metadata.artistCredits.orEmpty().none {
                    it.musicBrainzArtistId?.lowercase(Locale.ROOT)
                        ?.let(validMbid::matches) == true
                }
            ) return@mapNotNull null
            val title = metadata.title?.trim()
                ?.takeIf { canonicalStreamingTitleIsSafe(it) } ?: return@mapNotNull null
            val artist = metadata.artistDisplay?.trim()
                ?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            CanonicalCatalogSearchRow(
                musicBrainzId = id,
                kind = CanonicalSearchKind.RECORDING,
                title = title,
                subtitle = artist,
                durationMs = metadata.durationMs?.takeIf { it > 0L },
            )
        }.take(limit.coerceAtMost(12)).toList()
}
