package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ListenBrainzArtistTopRecording
import com.music.bitchord.data.catalog.metadata.RecordingMetadataEnrichment
import com.music.bitchord.data.catalog.metadata.canonicalStreamingTitleIsSafe
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import java.util.Locale

/** UI may only render a rank for the exact currently opened artist identity. */
internal data class CanonicalArtistPublicTopState(
    val artistMbid: String? = null,
    val rows: List<CanonicalCatalogSearchRow> = emptyList(),
)

private val PUBLIC_ARTIST_MBID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

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
        .filter { it.listeners > 0L && it.listens >= it.listeners }
        .distinctBy { it.recordingMbid.lowercase(Locale.ROOT) }
        .mapNotNull { rankedRecording ->
            val id = rankedRecording.recordingMbid.lowercase(Locale.ROOT)
            if (!PUBLIC_ARTIST_MBID.matches(id)) return@mapNotNull null
            val verified = verifiedByMbid[id] ?: return@mapNotNull null
            if (!verified.musicBrainzId.equals(id, ignoreCase = true)) return@mapNotNull null
            if (verified.artistCredits.orEmpty().none {
                    it.musicBrainzArtistId.equals(artist, ignoreCase = true)
                }
            ) return@mapNotNull null
            val title = verified.title?.trim()
                ?.takeIf { canonicalStreamingTitleIsSafe(it) } ?: return@mapNotNull null
            CanonicalCatalogSearchRow(
                musicBrainzId = id,
                kind = CanonicalSearchKind.RECORDING,
                title = title,
                subtitle = verified.artistDisplay.orEmpty(),
                artistScopeMbid = artist,
                durationMs = verified.durationMs?.takeIf { it > 0L },
            )
        }
        .take(limit.coerceAtMost(20))
        .toList()
}
