package com.music.bitchord.data.catalog.metadata

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface AppleArtworkEditionSource {
    suspend fun read(releaseMbid: String): MusicBrainzCatalogReleaseDetail?
}
internal fun interface AppleArtworkUpcSource {
    suspend fun lookup(barcode: String, storefront: String): ItunesCatalogResult
}
internal fun interface AppleArtworkLinkedAlbumSource {
    suspend fun lookup(collectionId: Long, storefront: String): ItunesCatalogResult
}

/**
 * On-demand, Android-only bridge from an exact MusicBrainz Release to a
 * promotional Apple Store album. Never title-search Apple to fabricate art.
 *
 * The UPC lookup proves that Apple supplies the album. A SECOND, complete MB
 * barcode candidate enumeration must uniquely certify the requested edition:
 * multiple MB releases can share the same UPC and matching track titles.
 * This is deliberately much stricter than the fallback CAA image path.
 */
internal class AppleVerifiedEditionArtworkResolver(
    private val edition: AppleArtworkEditionSource = AppleArtworkEditionSource { mbid ->
        when (val result = MusicBrainzCatalogGraph().lookupRelease(mbid)) {
            is MusicBrainzCatalogGraphResult.Success -> result.value
            is MusicBrainzCatalogGraphResult.Failure -> null
        }
    },
    private val upc: AppleArtworkUpcSource = AppleArtworkUpcSource { barcode, country ->
        ItunesCatalogClient().lookupUpc(barcode, country)
    },
    private val linked: AppleArtworkLinkedAlbumSource =
        AppleArtworkLinkedAlbumSource { appleId, country ->
            ItunesCatalogClient().lookupAlbum(appleId, country)
        },
    private val evidence: AppleAlbumEditionEvidenceSource =
        MusicBrainzBarcodeEditionEvidenceSource(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private data class Cached(val value: AppleVerifiedEditionArtwork?, val expiresAtMs: Long)
    private val cache = linkedMapOf<String, Cached>()
    // Each MB edition owns its own cache/reconciliation job. A global mutex
    // would make 16 artist cards queue behind the slowest unrelated barcode
    // lookup. Fixed stripes keep memory bounded while coalescing same-ID work.
    private val editionLocks = Array(32) { Mutex() }
    private val mbid = Regex("^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
    private val upcPattern = Regex("^[0-9]{8,14}$")

    suspend fun resolve(releaseMbid: String, storefront: String = "br"):
        AppleVerifiedEditionArtwork? {
        val id = releaseMbid.lowercase(Locale.ROOT)
        if (!mbid.matches(id) || !Regex("^[a-z]{2}$").matches(storefront))
            return null
        val key = "$storefront|$id"
        val stripe = (key.hashCode() and Int.MAX_VALUE) % editionLocks.size
        return editionLocks[stripe].withLock {
            val now = clockMs()
            // Lock only the short mutable map access, NEVER the network. An
            // edition's stripe covers its entire remote lookup.
            synchronized(cache) {
                cache[key]?.takeIf { it.expiresAtMs > now }
            }?.let { return@withLock it.value }
            val result = try {
                resolveUncached(id, storefront)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            synchronized(cache) {
                if (cache.size >= 48 && !cache.containsKey(key))
                    cache.remove(cache.keys.first())
                cache[key] = Cached(
                    result,
                    clockMs() + if (result == null) 15 * 60_000L else 6 * 60 * 60_000L,
                )
            }
            result
        }
    }

    private suspend fun resolveUncached(
        id: String, storefront: String,
    ): AppleVerifiedEditionArtwork? {
        val mb = edition.read(id) ?: return null
        if (!mb.musicBrainzId.equals(id, ignoreCase = true) ||
            !mb.status.equals("Official", ignoreCase = true) ||
            mb.groupMbid.isNullOrBlank() || !mb.hasCompleteTracklist
        ) return null
        // UPC remains the strict, independent primary route. For editions
        // without a usable barcode (or without an unambiguous UPC result),
        // only an explicit exact MB Release→Apple album URL may seed a direct
        // lookup. A title, artist or ReleaseGroup URL alone is never enough.
        val barcode = mb.barcode?.trim()?.takeIf(upcPattern::matches)
        val upcProof = if (barcode != null)
            resolveByBarcode(mb, id, storefront, barcode)
        else null
        return upcProof ?: resolveByExactReleaseLink(mb, id, storefront)
    }

    private suspend fun resolveByBarcode(
        mb: MusicBrainzCatalogReleaseDetail,
        id: String,
        storefront: String,
        barcode: String,
    ): AppleVerifiedEditionArtwork? {
        val appleLookup = upc.lookup(barcode, storefront)
        val candidateProof = appleVerifiedEditionArtwork(
            mb, appleLookup, storefront,
        ) ?: return null
        val source = appleLookup as? ItunesCatalogResult.Success ?: return null
        val apple = ItunesEditorialCatalog.assemble(storefront, source.items)
            .singleOrNull()?.copy(upc = barcode) ?: return null
        val gathered = evidence.discover(apple) ?: return null
        val match = AppleMusicBrainzEditionMatcher.match(
            apple, gathered.editions,
        ) as? AppleMusicBrainzEditionMatch.Verified ?: return null
        if (!match.musicBrainzReleaseId.equals(id, ignoreCase = true) ||
            !match.musicBrainzReleaseGroupId.equals(mb.groupMbid, ignoreCase = true) ||
            !match.appleScopedId.equals(candidateProof.appleScopedId, ignoreCase = true)
        ) return null
        return candidateProof
    }

    private suspend fun resolveByExactReleaseLink(
        mb: MusicBrainzCatalogReleaseDetail,
        id: String,
        storefront: String,
    ): AppleVerifiedEditionArtwork? {
        val appleId = mb.linkedAppleAlbumIds.distinct().singleOrNull()
            ?.takeIf { it > 0L } ?: return null
        val response = linked.lookup(appleId, storefront)
            as? ItunesCatalogResult.Success ?: return null
        if (response.possiblyTruncated) return null
        val albums = response.items.filter { it.type == ItunesCatalogItemType.ALBUM }
        val tracks = response.items.filter { it.type == ItunesCatalogItemType.TRACK }
        if (albums.size != 1 || tracks.isEmpty() ||
            response.items.size != 1 + tracks.size
        ) return null
        val source = albums.single()
        if (source.id != appleId || source.collectionId != appleId ||
            source.storefront != storefront ||
            source.trackCount == null || source.trackCount != tracks.size ||
            appleStoreLinkForExactProduct(
                source.storeUrl, ItunesCatalogItemType.ALBUM, appleId, storefront,
            ) == null ||
            safeAppleArtwork(source.artworkUrl) == null
        ) return null
        val apple = ItunesEditorialCatalog.assemble(storefront, response.items)
            .singleOrNull()?.takeIf {
                it.appleCollectionId == appleId &&
                    it.appleScopedId == "itunes:$storefront:album:$appleId"
            } ?: return null
        val proof = AppleMusicBrainzEditionMatcher.matchExplicitLinkedAlbum(
            apple, mb,
        ) as? AppleMusicBrainzEditionMatch.Verified ?: return null
        if (!proof.musicBrainzReleaseId.equals(id, ignoreCase = true) ||
            !proof.musicBrainzReleaseGroupId.equals(mb.groupMbid, ignoreCase = true) ||
            proof.appleScopedId != apple.appleScopedId
        ) return null
        return AppleVerifiedEditionArtwork(
            musicBrainzReleaseId = proof.musicBrainzReleaseId,
            musicBrainzReleaseGroupId = proof.musicBrainzReleaseGroupId,
            appleScopedId = proof.appleScopedId,
            artworkUrl = source.artworkUrl!!,
            storeUrl = source.storeUrl!!,
        )
    }
}
