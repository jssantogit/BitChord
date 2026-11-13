package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.BuildConfig
import kotlinx.coroutines.CancellationException

/**
 * A Discogs-owned edition, not a MusicBrainz ReleaseGroup or a new local
 * Recording/MediaAsset. The independent view must preserve both versions of
 * one Master rather than manufacture a one-to-one group mapping.
 *
 * Missing dates and disputed format classifications stay unknown.
 */
internal data class DiscogsStandaloneRelease(
    val releaseId: Long,
    val masterId: Long?,
    val title: String,
    val artistNames: List<String>,
    val year: Int?,
    val country: String?,
    val formats: List<String>,
    val kind: DiscogsEditorialKind?,
    val trackCount: Int,
    val unofficial: Boolean,
) {
    val browseId: String get() = "discogs:release:$releaseId"
    val attributionUrl: String get() = "https://www.discogs.com/release/$releaseId"
    val attributionText: String get() = "Data provided by Discogs."
    val musicBrainzId: String? get() = null
    val canPlay: Boolean get() = false
}

/**
 * In contrast with DiscogsShadowCatalog.select(), this projection MUST NOT
 * check whether a title already exists in MusicBrainz. This is a source-owned
 * catalog view and its coverage is to be measured independently.
 *
 * Artist association is the typed Discogs artist ID returned by Discogs
 * itself, never a same-name match or an inferred MusicBrainz MBID.
 */
internal object DiscogsStandaloneProjection {
    fun project(
        artistId: Long,
        records: List<DiscogsRecord>,
    ): List<DiscogsStandaloneRelease> {
        if (artistId !in 1..999_999_999_999L) return emptyList()
        return records.asSequence().take(24).filter { record ->
            record.link.kind == DiscogsKind.RELEASE &&
                record.artistIds.contains(artistId) &&
                record.artistNames.isNotEmpty() &&
                record.title.isNotBlank()
        }.distinctBy { it.link.id }.map { record ->
            DiscogsStandaloneRelease(
                releaseId = record.link.id,
                masterId = record.masterId,
                title = record.title.trim(),
                artistNames = record.artistNames.toList(),
                year = record.year,
                country = record.country,
                formats = record.formats.toList(),
                kind = discogsEditorialKind(record.formats),
                trackCount = record.tracklist.size,
                unofficial = record.unofficial,
            )
        }.sortedWith(
            compareByDescending<DiscogsStandaloneRelease> { it.year ?: Int.MIN_VALUE }
                .thenBy { it.title.lowercase(java.util.Locale.ROOT) }
                .thenBy { it.releaseId },
        ).toList()
    }
}

internal sealed interface DiscogsStandaloneArtistResult {
    data class Found(
        val releases: List<DiscogsStandaloneRelease>,
        val nextPage: Int?,
        /** At least one index row could not be hydrated or verified. */
        val partial: Boolean,
        /** The public endpoint allows us to request only the first 3 pages. */
        val truncatedByPageLimit: Boolean,
    ) : DiscogsStandaloneArtistResult

    data object Disabled : DiscogsStandaloneArtistResult
    data object InvalidRequest : DiscogsStandaloneArtistResult
    data object NotFound : DiscogsStandaloneArtistResult
    data object Malformed : DiscogsStandaloneArtistResult
    data object Unauthorized : DiscogsStandaloneArtistResult
    data object Unavailable : DiscogsStandaloneArtistResult
    data class Throttled(val retryAfterSeconds: Long?) : DiscogsStandaloneArtistResult
}

/**
 * Opt-in, metadata-only Discogs artist browse by an EXPLICIT Discogs Artist
 * ID. No MusicBrainz request, catalog-completeness requirement, scraped search,
 * token or synthetic ID is involved. The same process-wide limiter regulates
 * both artist-index and edition requests. This is a backend-only first slice:
 * it is intentionally not exposed as a selectable app catalog yet.
 *
 * An index page contains at most 12 entries. At most 3 pages are addressable
 * in this first version; partial/limit flags MUST be surfaced by later UI and
 * must not be interpreted as a complete artist discography.
 */
internal class DiscogsStandaloneArtistCatalog(
    private val index: DiscogsPublicArtistTransport = KtorDiscogsPublicArtistTransport(),
    private val editions: DiscogsClient = DiscogsClient(enabled = DiscogsEditionAccess.ENABLED),
    private val enabled: Boolean = false,
    private val limiter: DiscogsRateLimiter = DiscogsRequestBudget.limiter,
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
) {
    suspend fun browse(artistId: Long, page: Int): DiscogsStandaloneArtistResult {
        if (!enabled) return DiscogsStandaloneArtistResult.Disabled
        if (artistId !in 1..999_999_999_999L || page !in 1..3) {
            return DiscogsStandaloneArtistResult.InvalidRequest
        }

        val response = try {
            limiter.runHttpLimited { index.list(artistId, page, userAgent) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DiscogsStandaloneArtistResult.Unavailable
        }
        when (response.status) {
            200 -> Unit
            401, 403 -> return DiscogsStandaloneArtistResult.Unauthorized
            404 -> return DiscogsStandaloneArtistResult.NotFound
            429 -> return DiscogsStandaloneArtistResult.Throttled(response.retryAfterSeconds)
            else -> return DiscogsStandaloneArtistResult.Unavailable
        }
        val found = DiscogsPublicArtistParsing.page(response.body, page)
            ?: return DiscogsStandaloneArtistResult.Malformed

        val observed = ArrayList<DiscogsRecord>()
        val releaseIds = HashSet<Long>()
        var partial = false
        for (entry in found.entries.take(12)) {
            val editionId = when (entry.kind) {
                DiscogsKind.RELEASE -> entry.id
                DiscogsKind.MASTER -> {
                    val master = editions.fetchMaster(entry.id)
                        as? DiscogsLookupResult.FoundMaster
                    if (master == null) {
                        partial = true
                        continue
                    }
                    master.record.mainReleaseId ?: run {
                        partial = true
                        continue
                    }
                }
            }
            if (!releaseIds.add(editionId)) continue
            when (val fetched = editions.fetchRelease(editionId)) {
                is DiscogsLookupResult.Found -> {
                    val record = fetched.record
                    if ((entry.kind == DiscogsKind.MASTER &&
                            record.masterId != entry.id) ||
                        record.artistIds.none { it == artistId }
                    ) {
                        partial = true
                    } else observed += record
                }
                // Missing, stale, malformed and network failures are UNKNOWN
                // coverage, not evidence that the provider has no release.
                else -> partial = true
            }
        }

        return DiscogsStandaloneArtistResult.Found(
            releases = DiscogsStandaloneProjection.project(artistId, observed),
            nextPage = (page + 1).takeIf {
                it <= found.totalPages && it <= 3
            },
            partial = partial,
            truncatedByPageLimit = found.totalPages > 3,
        )
    }
}
