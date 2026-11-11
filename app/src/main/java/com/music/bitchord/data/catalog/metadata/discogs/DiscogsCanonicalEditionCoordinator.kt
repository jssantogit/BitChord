package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.data.catalog.MusicBrainzReleaseEnrichmentEntity
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * A ReleaseGroup is a work, not a Discogs edition. A chosen MusicBrainz
 * Release may annotate it only when the native catalog explicitly supplies
 * both the exact Release and its verified parent ReleaseGroup identities.
 *
 * This is a presentation routing gate, NOT a new identity resolution method:
 * the coordinator will independently re-check Official status, complete
 * Track graph and the exact Discogs relationship before any fields are shown.
 */
internal fun discogsOnlineAlbumEditionMbid(
    browseId: String,
    selectedReleaseMbid: String?,
    selectedReleaseGroupMbid: String?,
): String? {
    discogsOnlineReleaseMbid(browseId)?.let { return it }
    val prefix = "catalog:RELEASE_GROUP:"
    if (!browseId.startsWith(prefix)) return null
    val groupMbid = normalizedReleaseMbid(browseId.removePrefix(prefix)) ?: return null
    if (normalizedReleaseMbid(selectedReleaseGroupMbid) != groupMbid) return null
    return normalizedReleaseMbid(selectedReleaseMbid)
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
        !enrichment?.status.equals("Official", ignoreCase = true)
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
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Cached(
        val result: DiscogsEditionEnrichment,
        val expiresAtMillis: Long,
    )

    // Coalesce concurrent navigation into one read and keep a bounded
    // nonpersistent result cache. A source observation NEVER gains additional
    // freshness from the coordinator; the five-hour Discogs TTL is absolute.
    private val lock = Mutex()
    private val entries = linkedMapOf<String, Cached>()

    /** Rollback clears all source-scoped caches, never MusicBrainz or Room. */
    suspend fun clear() {
        // Avoid coordinator-lock -> corroborator-lock inversion: corroboration
        // can legitimately hold its own mutex while observing an edition.
        DiscogsGroupCorroborator.clear()
        lock.withLock {
            entries.clear()
            editions.clearCachedEvidence()
            DiscogsGroupEditorialEvidence.store.clear()
            DiscogsSourceOnlyDiscovery.store.clear()
        }
    }

    suspend fun observeRelease(musicBrainzReleaseMbid: String): DiscogsEditionEnrichment {
        if (!enabled) return DiscogsEditionEnrichment.Disabled
        val mbid = normalizedReleaseMbid(musicBrainzReleaseMbid)
            ?: return DiscogsEditionEnrichment.Invalid
        return lock.withLock {
            val now = nowMillis()
            val cached = entries[mbid]
            if (cached != null && now < cached.expiresAtMillis &&
                (cached.result !is DiscogsEditionEnrichment.Verified ||
                    cached.result.observation.isDisplayableAt(now))
            ) return@withLock cached.result

            entries.remove(mbid)
            val resolved = resolve(mbid)
            DiscogsDiagnosticStatus.releaseResult(resolved)
            val lifetime = when (resolved) {
                is DiscogsEditionEnrichment.Verified ->
                    minOf(20L * 60_000L,
                        (resolved.observation.fetchedAtMillis +
                            DiscogsObservation.TTL_MILLIS - now).coerceAtLeast(0L))
                DiscogsEditionEnrichment.NoLink -> 5L * 60_000L
                DiscogsEditionEnrichment.Candidate,
                DiscogsEditionEnrichment.Conflict,
                DiscogsEditionEnrichment.Ambiguous,
                DiscogsEditionEnrichment.Invalid -> 60_000L
                DiscogsEditionEnrichment.Unavailable -> 15_000L
                DiscogsEditionEnrichment.Disabled -> 0L
            }
            if (lifetime > 0L) {
                entries[mbid] = Cached(resolved, now + lifetime)
                while (entries.size > 64) entries.remove(entries.keys.first())
            }
            resolved
        }
    }

    private suspend fun resolve(mbid: String): DiscogsEditionEnrichment {
        val graphResult = graph.lookupRelease(mbid)
        val detail = (graphResult as? MusicBrainzCatalogGraphResult.Success<
            MusicBrainzCatalogReleaseDetail>)?.value ?: return DiscogsEditionEnrichment.Unavailable
        if (!detail.hasCompleteTracklist || detail.tracks.isEmpty() ||
            detail.artistDisplay.isNullOrBlank() ||
            // A missing or partially invalid structured credit can silently
            // drop a second artist in the human-readable display string.
            // Never corroborate an incomplete list as an exact edition.
            detail.artistCreditNames.isEmpty() ||
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
            country = detail.country,
            releaseYear = detail.releaseDate?.take(4)
                ?.toIntOrNull()?.takeIf { it in 1800..2200 },
            creditedArtistNames = detail.artistCreditNames,
            musicBrainzReleaseGroupId = detail.groupMbid,
        )
        val result = editions.observe(canonical)
        if (result is DiscogsEditionEnrichment.Verified) {
            val now = nowMillis()
            val kind = DiscogsEditionPresentation.from(result, now)?.editorialKind
            // An exact MusicBrainz edition graph independently anchors its
            // parent group. Source facts remain volatile and UI-only.
            DiscogsGroupEditorialEvidence.store.record(
                releaseGroupMbid = canonical.musicBrainzReleaseGroupId,
                discogsReleaseId = result.observation.record.link.id,
                kind = kind,
                verified = true,
                expiresAtMillis = result.observation.fetchedAtMillis + DiscogsObservation.TTL_MILLIS,
                nowMillis = now,
                masterId = result.observation.record.masterId,
            )
        }
        return result
    }
}

/**
 * Personal Android installation: active metadata-only requests to Discogs'
 * documented public Release API. Distribution/publication is not currently a
 * goal and would require a separate rights/API policy review.
 *
 * Even for private use, respect rate limits, the 5h freshness deadline, the
 * linked attribution adjacent to metadata and this non-affiliation notice.
 * No images, marketplace/user data, credentials or audio are fetched.
 * This is not an audio-source setting or an invitation to scrape Discogs.
 */
internal object DiscogsEditionAccess {
    const val ENABLED = true
    const val NON_AFFILIATION_NOTICE =
        "This application uses Discogs’ API but is not affiliated with, sponsored or endorsed by Discogs. " +
            "‘Discogs’ is a trademark of Zink Media, LLC."

    @Volatile private var searchTokenVault: DiscogsSearchTokenVault? = null

    /** Install application context, never a token, before first UI composition. */
    fun configureSearchCredentials(context: android.content.Context) {
        searchTokenVault = DiscogsSearchTokenVault(context.applicationContext)
    }

    fun isSearchConfigured(): Boolean = searchTokenVault?.configured() == true

    private val publicClient: DiscogsClient by lazy {
        DiscogsClient(enabled = ENABLED)
    }

    private val searchClient: DiscogsDatabaseSearchClient by lazy {
        DiscogsDatabaseSearchClient(
            enabled = ENABLED,
            tokenProvider = { searchTokenVault?.readForSearch() },
        )
    }

    val discovery: DiscogsAuthenticatedDiscovery by lazy {
        DiscogsAuthenticatedDiscovery(searchClient, publicClient)
    }

    /** Anonymous, exact Artist relation-only index; no credential is sent. */
    val publicArtistDiscovery: DiscogsPublicArtistDiscovery by lazy {
        DiscogsPublicArtistDiscovery(
            editions = publicClient,
            enabled = ENABLED,
        )
    }

    val coordinator: DiscogsCanonicalEditionCoordinator by lazy {
        DiscogsCanonicalEditionCoordinator(
            editions = DiscogsEditionRepository(
                discogs = publicClient,
                authenticatedDiscovery = discovery,
                enabled = ENABLED,
            ),
            enabled = ENABLED,
        )
    }
}
