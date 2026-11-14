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
        val barcode = mb.barcode?.trim()?.takeIf(upcPattern::matches) ?: return null
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
}
