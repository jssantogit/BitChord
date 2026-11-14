package com.music.bitchord.data.catalog.metadata

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionAccess
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment

/** Bounded candidates gathered from MusicBrainz; never products to display. */
internal data class AppleAlbumEditionEvidence(
    val editions: List<MusicBrainzCatalogReleaseDetail>,
    val groups: List<MusicBrainzReleaseGroupHit>,
    /**
     * Comes ONLY from a separate, exact iTunes public UPC reverse lookup.
     * Not filled from MusicBrainz barcode alone or a title-based search.
     */
    val verifiedAppleUpc: String? = null,
)

internal fun interface AppleAlbumEditionEvidenceSource {
    suspend fun discover(album: ItunesEditorialRelease): AppleAlbumEditionEvidence?
}

/**
 * Network-side candidate discovery. The barcode indexes possible MB Releases,
 * not a verified crosswalk. Missing, truncated or malformed search results
 * cannot certify that a better matching edition was not omitted.
 */
internal class MusicBrainzBarcodeEditionEvidenceSource(
    private val search: MusicBrainzCatalogSearch = MusicBrainzCatalogSearch(),
    private val graph: MusicBrainzCatalogGraph = MusicBrainzCatalogGraph(),
    private val upcVerifier: AppleUpcReverseLookupVerifier =
        AppleUpcReverseLookupVerifier(),
) : AppleAlbumEditionEvidenceSource {
    private val suffix = Regex("""\s[-–—]\s(?:EP|Single)$""", RegexOption.IGNORE_CASE)
    private val space = Regex("""\s+""")
    private fun normalized(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
            .replace(space, " ").lowercase(Locale.ROOT)

    override suspend fun discover(album: ItunesEditorialRelease): AppleAlbumEditionEvidence? {
        if (album.upc == null) return discoverByReverseUpc(album)
        val barcode = album.upc.takeIf { Regex("^[0-9]{8,14}$").matches(it) }
            ?: return null
        val appleTitle = normalized(album.title.replace(suffix, ""))
        val appleArtist = normalized(album.artistName)
        val relevant = collectCompleteMusicBrainzEditionCandidates(
            maxRelevant = 8,
            isRelevant = { hit ->
                isCuratedMusicBrainzRelease(hit) &&
                    normalized(hit.title) == appleTitle &&
                    normalized(hit.artistDisplay.orEmpty()) == appleArtist
            },
            searchPage = { limit, offset ->
                search.searchReleasesByBarcode(barcode, limit, offset)
            },
        ) ?: return null
        if (relevant.isEmpty() ||
            relevant.any { it.artistCreditMbids.isNullOrEmpty() }
        ) return null

        val editions = ArrayList<MusicBrainzCatalogReleaseDetail>(relevant.size)
        for (hit in relevant) {
            val lookup = graph.lookupRelease(hit.musicBrainzId)
            val detail = when (lookup) {
                is MusicBrainzCatalogGraphResult.Success -> lookup.value
                is MusicBrainzCatalogGraphResult.Failure -> return null
            }
            // The indexed candidate must be the exact edition that was read.
            if (detail.musicBrainzId != hit.musicBrainzId ||
                detail.isVideoOnlyContent == true
            ) return null
            editions += detail
        }
        val verified = AppleMusicBrainzEditionMatcher.match(album, editions)
            as? AppleMusicBrainzEditionMatch.Verified
            ?: return null
        val group = when (val lookup = graph.lookupReleaseGroup(
            verified.musicBrainzReleaseGroupId,
        )) {
            is MusicBrainzCatalogGraphResult.Success -> listOf(lookup.value)
            is MusicBrainzCatalogGraphResult.Failure -> emptyList()
        }
        return AppleAlbumEditionEvidence(editions, group)
    }

    /**
     * Missing UPC in Apple's ID lookup is not a reason to invent one from MB.
     * Discover a *complete* small candidate set by text, find a single
     * independently matchable official MB edition, and ask Apple itself:
     * lookup?upc=<MB barcode>&entity=song&country=<same storefront>.
     * Only the same Apple album ID, artist and entire ordered tracklist can
     * turn this candidate into a barcode proof; that proof is internal and
     * never replaces the originally returned Apple UPC field.
     */
    private suspend fun discoverByReverseUpc(
        album: ItunesEditorialRelease,
    ): AppleAlbumEditionEvidence? {
        val title = album.title.replace(suffix, "").trim()
        val artist = album.artistName.trim()
        if (title.length !in 2..100 || artist.length !in 1..100 ||
            title.any(Char::isISOControl) || artist.any(Char::isISOControl)
        ) return null
        val appleTitle = normalized(title)
        val appleArtist = normalized(artist)
        val relevant = collectCompleteMusicBrainzEditionCandidates(
            maxRelevant = 4,
            isRelevant = { hit ->
                isCuratedMusicBrainzRelease(hit) &&
                    normalized(hit.title) == appleTitle &&
                    normalized(hit.artistDisplay.orEmpty()) == appleArtist
            },
            searchPage = { limit, offset ->
                search.searchOfficialReleasesForTitleArtist(
                    title, artist, limit, offset,
                )
            },
        ) ?: return null
        // A 4-page / 48-row source ceiling protects the app from extremely
        // large clusters. At most four relevant MB graphs reach Apple lookup.
        if (relevant.isEmpty() ||
            relevant.any { it.artistCreditMbids.isNullOrEmpty() }
        ) return null

        val editions = ArrayList<MusicBrainzCatalogReleaseDetail>(relevant.size)
        for (hit in relevant) {
            val lookup = graph.lookupRelease(hit.musicBrainzId)
            val detail = when (lookup) {
                is MusicBrainzCatalogGraphResult.Success -> lookup.value
                is MusicBrainzCatalogGraphResult.Failure -> return null
            }
            if (detail.musicBrainzId != hit.musicBrainzId ||
                detail.isVideoOnlyContent == true
            ) return null
            editions += detail
        }
        // Keep the full candidate set in the matcher, even when a different
        // MB edition has missing barcode evidence (which can veto uniqueness).
        val compatibleUpcs = editions.mapNotNull { edition ->
            val code = edition.barcode?.trim()
                ?.takeIf { Regex("^[0-9]{8,14}$").matches(it) }
                ?: return@mapNotNull null
            val result = AppleMusicBrainzEditionMatcher.match(
                album.copy(upc = code), editions,
            ) as? AppleMusicBrainzEditionMatch.Verified
            if (result?.musicBrainzReleaseId == edition.musicBrainzId) code else null
        }.distinct()
        val barcode = compatibleUpcs.singleOrNull() ?: return null
        if (!upcVerifier.verifies(album, barcode)) return null
        val exactLink = AppleMusicBrainzEditionMatcher.match(
            album.copy(upc = barcode), editions,
        ) as? AppleMusicBrainzEditionMatch.Verified ?: return null
        val groups = when (val result = graph.lookupReleaseGroup(
            exactLink.musicBrainzReleaseGroupId,
        )) {
            is MusicBrainzCatalogGraphResult.Success -> listOf(result.value)
            is MusicBrainzCatalogGraphResult.Failure -> emptyList()
        }
        return AppleAlbumEditionEvidence(
            editions = editions, groups = groups, verifiedAppleUpc = barcode,
        )
    }
}

/**
 * Android album-detail evidence bridge. Rebuild only the *original* Apple
 * collection and its exact tracks for the existing three-source reconciler.
 * No external source can insert products or replace artwork, names or tracks.
 */
/** Discogs is invoked only AFTER exact Apple↔MB Release certification. */
internal fun interface AppleDiscogsEditionEvidenceSource {
    suspend fun observe(musicBrainzReleaseId: String): DiscogsEditionEnrichment
}

internal class AppleAlbumEditionReconciliationService(
    private val source: AppleAlbumEditionEvidenceSource =
        MusicBrainzBarcodeEditionEvidenceSource(),
    private val discogs: AppleDiscogsEditionEvidenceSource =
        AppleDiscogsEditionEvidenceSource { id ->
            DiscogsEditionAccess.coordinator.observeRelease(id)
        },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun resolve(album: ItunesEditorialRelease): AppleTriSourceEditorialEntry? {
        if (album.appleCollectionId <= 0L ||
            album.artistId == null || album.artistId <= 0L ||
            album.appleScopedId != "itunes:${album.storefront}:album:${album.appleCollectionId}" ||
            !Regex("^[a-z]{2}$").matches(album.storefront) ||
            album.artistName.isBlank() || album.title.isBlank() ||
            album.upc?.matches(Regex("^[0-9]{8,14}$")) == false ||
            album.trackCount == null || album.trackCount <= 0 ||
            album.tracks.size != album.trackCount
        ) return null
        if (album.tracks.any { track ->
                track.type != ItunesCatalogItemType.TRACK ||
                    track.collectionId != album.appleCollectionId ||
                    track.storefront != album.storefront ||
                    track.id <= 0L || track.discNumber == null ||
                    track.discNumber <= 0 || track.trackNumber == null ||
                    track.trackNumber <= 0
            } || album.tracks.map { it.scopedId }.distinct().size != album.tracks.size ||
            album.tracks.map { it.discNumber to it.trackNumber }.distinct().size !=
            album.tracks.size
        ) return null

        val data = try {
            source.discover(album)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val candidateUpc = album.upc ?: data.verifiedAppleUpc
            ?.takeIf { Regex("^[0-9]{8,14}$").matches(it) }
        // A candidate MB barcode must NEVER substitute for proof from the
        // Apple UPC endpoint when the original Apple album omitted the field.
        if (candidateUpc == null) return null
        val rawAlbum = ItunesCatalogItem(
            type = ItunesCatalogItemType.ALBUM, id = album.appleCollectionId,
            storefront = album.storefront, title = album.title,
            artistName = album.artistName, artistId = album.artistId,
            collectionId = album.appleCollectionId, storeUrl = album.storeUrl,
            releaseDate = album.releaseDate, genre = album.genre,
            durationMs = null, trackNumber = null, discNumber = null,
            artworkUrl = album.artworkUrl, upc = candidateUpc,
            trackCount = album.trackCount,
        )
        fun reconcile(discogsObservations: List<DiscogsEditionEnrichment>) =
            AppleTriSourceEditorialReconciler.reconcile(
                storefront = album.storefront,
                appleRows = listOf(rawAlbum) + album.tracks,
                mbEditions = data.editions,
                mbGroups = data.groups,
                discogsObservations = discogsObservations,
                nowMillis = nowMillis(),
            ).singleOrNull()

        // The internal, separately verified barcode is a matching hint.
        // It is not a missing field that can be fabricated on Apple products.
        fun keepOriginalAppleFields(row: AppleTriSourceEditorialEntry) =
            if (row.release.upc == album.upc) row else
                row.copy(release = row.release.copy(upc = album.upc))
        val first = keepOriginalAppleFields(
            reconcile(emptyList()) ?: return null,
        )
        val exactLink = first.match as? AppleMusicBrainzEditionMatch.Verified
            ?: return null
        if (first.release.appleScopedId != album.appleScopedId ||
            first.release.title != album.title ||
            first.release.artworkUrl != album.artworkUrl ||
            first.release.tracks != album.tracks
        ) return null

        // The opt-in Discogs edition coordinator independently verifies
        // canonical release graph, exact Discogs Release relation, tracklist
        // and freshness. A failed/ambiguous observation leaves MB intact.
        val observed = try {
            discogs.observe(exactLink.musicBrainzReleaseId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DiscogsEditionEnrichment.Unavailable
        }
        if (observed !is DiscogsEditionEnrichment.Verified) return first
        val enriched = keepOriginalAppleFields(
            reconcile(listOf(observed)) ?: return first,
        )
        return if (enriched.match == first.match &&
            enriched.release.appleScopedId == album.appleScopedId &&
            enriched.release.title == album.title &&
            enriched.release.artworkUrl == album.artworkUrl &&
            enriched.release.tracks == album.tracks
        ) enriched else first
    }
}
