package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.data.catalog.MusicBrainzReleaseEnrichmentEntity
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import java.util.Locale

private val EXACT_RELEASE_MBID =
    Regex("^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")

private fun normalizedReleaseMbid(raw: String?): String? =
    raw?.trim()?.lowercase(Locale.ROOT)?.takeIf(EXACT_RELEASE_MBID::matches)

/**
 * Online release route is a typed MusicBrainz Release, never a ReleaseGroup,
 * artist, local file path or a synthetic navigation fragment.
 */
internal fun discogsOnlineReleaseMbid(browseId: String): String? {
    val prefix = "catalog:RELEASE:"
    if (!browseId.startsWith(prefix)) return null
    return normalizedReleaseMbid(browseId.removePrefix(prefix))
}

/**
 * Unified Library ReleaseEntity IDs are *not* MBIDs. Only a persisted,
 * matching MusicBrainz release-enrichment edge may resolve them.
 */
internal fun discogsLibraryReleaseMbid(
    localReleaseId: String,
    enrichment: MusicBrainzReleaseEnrichmentEntity?,
): String? {
    if (enrichment?.releaseId != localReleaseId ||
        !enrichment.status.equals("Official", ignoreCase = true)
    ) return null
    return normalizedReleaseMbid(enrichment.mbid)
}

/**
 * Opt-in coordinator: obtains release-specific track, artist, barcode and
 * status evidence from the MusicBrainz graph before consulting Discogs.
 * Never use the visible page's artist/title or locally available audio rows
 * as a substitute for the full, independently verified edition tracklist.
 */
internal class DiscogsCanonicalEditionCoordinator(
    private val graph: MusicBrainzCatalogGraph = MusicBrainzCatalogGraph(),
    private val editions: DiscogsEditionRepository = DiscogsEditionRepository(),
    private val enabled: Boolean = false,
) {
    suspend fun observeRelease(musicBrainzReleaseMbid: String): DiscogsEditionEnrichment {
        if (!enabled) return DiscogsEditionEnrichment.Disabled
        val mbid = normalizedReleaseMbid(musicBrainzReleaseMbid)
            ?: return DiscogsEditionEnrichment.Invalid
        val graphResult = graph.lookupRelease(mbid)
        val detail = (graphResult as? MusicBrainzCatalogGraphResult.Success<
            MusicBrainzCatalogReleaseDetail>)?.value ?: return DiscogsEditionEnrichment.Unavailable
        if (!detail.hasCompleteTracklist || detail.tracks.isEmpty() ||
            detail.artistDisplay.isNullOrBlank() ||
            !detail.status.equals("Official", ignoreCase = true)
        ) return DiscogsEditionEnrichment.Candidate

        val canonical = CanonicalEditionEvidence(
            musicBrainzReleaseId = detail.musicBrainzId,
            title = detail.title,
            creditedArtist = detail.artistDisplay,
            status = detail.status,
            barcodes = listOfNotNull(detail.barcode?.takeIf(String::isNotBlank)),
            trackTitles = detail.tracks.sortedWith(
                compareBy({ it.mediumNumber }, { it.trackNumber }),
            ).map { it.title },
        )
        return editions.observe(canonical)
    }
}

/**
 * Deliberately closed pending definitive legal/API approval and Android smoke.
 * Both outer coordinator and inner network client must be opened together.
 * This is not a user-configurable preference and must not be implicitly
 * enabled by changing music-source options.
 */
internal object DiscogsEditionAccess {
    const val ENABLED = false

    val coordinator: DiscogsCanonicalEditionCoordinator by lazy {
        DiscogsCanonicalEditionCoordinator(
            editions = DiscogsEditionRepository(
                discogs = DiscogsClient(enabled = ENABLED),
                enabled = ENABLED,
            ),
            enabled = ENABLED,
        )
    }
}
