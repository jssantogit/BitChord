package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ListenBrainzArtistTopRecording
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.bitchord.data.catalog.metadata.publicListenBrainzRecordingRanking
import com.music.bitchord.data.catalog.metadata.RecordingMetadataEnrichment
import com.music.bitchord.data.catalog.metadata.canonicalStreamingTitleIsSafe
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** UI may only render a rank for the exact currently opened artist identity. */
internal data class CanonicalArtistPublicTopState(
    val artistMbid: String? = null,
    val rows: List<CanonicalCatalogSearchRow> = emptyList(),
    /** Successful public fetch time, including a verified empty upstream chart. */
    val fetchedAtMs: Long? = null,
    /** True for a limited MusicBrainz candidate sample, not a complete chart. */
    val sampled: Boolean = false,
)

/** A visible artist rank cannot outlive the shared public popularity TTL. */
internal fun canonicalArtistPublicTopIsFresh(
    artistMbid: String,
    state: CanonicalArtistPublicTopState,
    nowMs: Long,
): Boolean {
    val fetched = state.fetchedAtMs ?: return false
    return artistMbid.equals(state.artistMbid, ignoreCase = true) &&
        nowMs >= fetched && nowMs - fetched < 
            com.music.bitchord.data.catalog.metadata.LISTENBRAINZ_PUBLIC_TTL_MS
}

private val PUBLIC_ARTIST_MBID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** The chart favors official studio recordings, not practice, live or fan
 * versions, even when MusicBrainz has a separate Recording for them. */
private val PUBLIC_ARTIST_CHART_NON_STUDIO = Regex(
    """(?i)(?:\s[-–—]\s*(?:live|karaoke|demo|rehearsal|fan[- ]?edit|bootleg)(?:\b|$)|\((?:live|karaoke|demo|rehearsal|fan[- ]?edit|bootleg)(?:\W|$))"""
)


/**
 * When the per-artist popularity endpoint is temporarily unavailable (401),
 * use the public all-time sitewide ranking *only* for recordings whose source
 * row explicitly credits the exact MusicBrainz artist ID. The candidate
 * set is globally bounded: it is stronger than arbitrary first-page search,
 * but it is not equivalent to Spotify's full all-time artist chart.
 *
 * Sitewide provides listens, not unique listeners. Preserve that unknown.
 * Official studio edition proof is still required independently by
 * canonicalVerifiedArtistTopRows before any card becomes visible.
 */
internal fun canonicalArtistSitewideAllTimeRanking(
    artistMbid: String,
    observations: List<ListenBrainzSitewideRecording>,
    limit: Int = 20,
): List<ListenBrainzArtistTopRecording> {
    val artist = artistMbid.lowercase(Locale.ROOT)
    if (!PUBLIC_ARTIST_MBID.matches(artist) || limit <= 0) return emptyList()
    return observations.asSequence()
        .filter { it.listenCount > 0L && it.artistMbids.any { id ->
            id.equals(artist, ignoreCase = true)
        } }
        .filter { PUBLIC_ARTIST_MBID.matches(it.recordingMbid.lowercase(Locale.ROOT)) }
        // A duplicated MBID must retain its strongest observed count rather
        // than whichever page arrived first. Otherwise the rank is incorrect.
        .sortedWith(compareByDescending<ListenBrainzSitewideRecording> { it.listenCount }
            .thenBy { it.recordingMbid })
        .distinctBy { it.recordingMbid.lowercase(Locale.ROOT) }
        .take(limit.coerceAtMost(20))
        .map { ListenBrainzArtistTopRecording(
            it.recordingMbid.lowercase(Locale.ROOT), it.listenCount, listeners = null,
        ) }
        .toList()
}

/**
 * MusicBrainz recording verification remains sequential and bounded by the
 * existing request limiter. Once a row is independently verified, its cover
 * may load in the background while the next row is checked. Slow CAA lookups
 * must not hold up the titles of subsequent popular recordings.
 *
 * Cover workers are structured children of the artist request. Cancellation
 * propagates to them, and no more than three covers load concurrently.
 * Callers own the identity checks and synchronized state publication.
 */
internal suspend fun <T> canonicalProcessArtistTopInParallel(
    candidates: List<T>,
    maxConcurrentCovers: Int = 3,
    verifyAndPublish: suspend (T) -> Boolean,
    enrichCoverAndPublish: suspend (T) -> Unit,
) = coroutineScope {
    require(maxConcurrentCovers in 1..8)
    val slots = Semaphore(maxConcurrentCovers)
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        if (verifyAndPublish(candidate)) {
            launch {
                slots.withPermit { enrichCoverAndPublish(candidate) }
            }
        }
    }
}

/**
 * ListenBrainz supplies popularity order and scope. MusicBrainz must
 * independently prove BOTH recording identity and exact artist credit.
 *
 * All cards are read-only catalog metadata: no synthesized Song, stream,
 * thumbnail, audio entitlement or local rating ID. Rankings can be hidden
 * entirely when the authoritative metadata is missing.
 */
internal fun canonicalVerifiedArtistTopRows(
    artistMbid: String,
    ranked: List<ListenBrainzArtistTopRecording>,
    musicBrainzMetadata: Map<String, RecordingMetadataEnrichment>,
    limit: Int = 12,
): List<CanonicalCatalogSearchRow> {
    val artist = artistMbid.lowercase(Locale.ROOT)
    if (!PUBLIC_ARTIST_MBID.matches(artist) || limit <= 0) return emptyList()
    val verifiedByMbid = musicBrainzMetadata.entries.associate { (id, data) ->
        id.lowercase(Locale.ROOT) to data
    }
    return ranked.asSequence()
        .filter { it.listens > 0L && (it.listeners?.let { n -> n > 0L && it.listens >= n } ?: true) }
        // Reassert numeric order at the UI boundary, even when the caller
        // supplies paged or partially enriched candidates in search order.
        .sortedWith(compareByDescending<ListenBrainzArtistTopRecording> { it.listens }
            .thenByDescending { it.listeners ?: -1L }.thenBy { it.recordingMbid })
        .distinctBy { it.recordingMbid.lowercase(Locale.ROOT) }
        .mapNotNull { rankedRecording ->
            val id = rankedRecording.recordingMbid.lowercase(Locale.ROOT)
            if (!PUBLIC_ARTIST_MBID.matches(id)) return@mapNotNull null
            val verified = verifiedByMbid[id] ?: return@mapNotNull null
            if (!verified.musicBrainzId.equals(id, ignoreCase = true) ||
                verified.officialAudioReleaseMbid == null
            ) return@mapNotNull null
            if (verified.artistCredits.orEmpty().none {
                    it.musicBrainzArtistId.equals(artist, ignoreCase = true)
                }
            ) return@mapNotNull null
            val title = verified.title?.trim()
                ?.takeIf { canonicalStreamingTitleIsSafe(it) &&
                    !PUBLIC_ARTIST_CHART_NON_STUDIO.containsMatchIn(it) }
                ?: return@mapNotNull null
            // A ListenBrainz CAA hint may refer to another edition of the
            // same recording. Prefer our independently proved Official sleeve;
            // otherwise accept the hint only for the EXACT proved release.
            val officialEdition = verified.officialAudioReleaseMbid
            val cover = verified.officialAudioArtworkUri
                ?: rankedRecording.artworkUri?.takeIf {
                    it.startsWith("https://coverartarchive.org/release/$officialEdition/")
                }
            CanonicalCatalogSearchRow(
                musicBrainzId = id,
                kind = CanonicalSearchKind.RECORDING,
                title = title,
                subtitle = verified.artistDisplay.orEmpty(),
                artistScopeMbid = artist,
                artworkUri = cover,
                durationMs = verified.durationMs?.takeIf { it > 0L },
            )
        }
        .take(limit.coerceAtMost(20))
        .toList()
}

/** Only a MusicBrainz Recording MBID with an independently attached
 * canonical MediaAsset may offer playback. This presentation evidence never
 * creates an asset or bypasses the playback executor's authorization checks. */
internal fun canonicalArtistTopPlaybackDecoration(
    rows: List<CanonicalCatalogSearchRow>,
    assetRecordingMbids: Set<String>,
): List<CanonicalCatalogSearchRow> {
    val available = assetRecordingMbids.map { it.lowercase(Locale.ROOT) }.toSet()
    return rows.map { row ->
        if (row.kind == CanonicalSearchKind.RECORDING &&
            row.musicBrainzId.lowercase(Locale.ROOT) in available
        ) row.copy(hasCatalogedAsset = true) else row
    }
}

/**
 * When artist-top is unavailable, a bounded MusicBrainz sample can be
 * ordered by ACTUAL ListenBrainz counts from /popularity/recording.
 * It is not a comprehensive artist chart and the UI must label it as such.
 * Exact MusicBrainz credit checks still run before any candidate is visible.
 */
internal fun canonicalArtistSampledPopularityRanking(
    recordingMbids: List<String>,
    observations: List<ListenBrainzPopularityEvidence>,
): List<ListenBrainzArtistTopRecording> {
    val eligible = recordingMbids.take(100).map { it.lowercase(Locale.ROOT) }.toSet()
    return observations.filterIsInstance<ListenBrainzPopularityEvidence.Known>()
        .filter { it.recordingMbid.lowercase(Locale.ROOT) in eligible &&
            it.listeners > 0L && it.listens >= it.listeners }
        // "Mais tocadas" means listen counts, not number of unique listeners.
        .sortedWith(compareByDescending<ListenBrainzPopularityEvidence.Known> { it.listens }
            .thenByDescending { it.listeners }.thenBy { it.recordingMbid })
        .map { ListenBrainzArtistTopRecording(it.recordingMbid, it.listens, it.listeners) }
        .take(8)
}
