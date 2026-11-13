package com.music.bitchord.data.catalog.metadata

import java.util.Locale

/**
 * Apple supplies the list of visible commercial releases, their names, track
 * order and artwork. MusicBrainz is an optional identity annotation; it must
 * never create/remove an Apple editorial release or override its presentation.
 *
 * This is an isolated projection, not yet the live Home/artist/search source.
 */
internal enum class ItunesEditorialReleaseFormat { ALBUM, EP, SINGLE, UNCLASSIFIED }

internal data class ItunesVerifiedReleaseGroupLink(
    val appleScopedId: String,
    val musicBrainzReleaseGroupId: String,
)

internal data class ItunesEditorialRelease(
    val appleScopedId: String,
    val appleCollectionId: Long,
    val storefront: String,
    val title: String,
    val artistName: String,
    val artistId: Long?,
    val releaseDate: String?,
    val genre: String?,
    val format: ItunesEditorialReleaseFormat,
    val artworkUrl: String?,
    val trackCount: Int?,
    val tracks: List<ItunesCatalogItem>,
    val musicBrainzReleaseGroupId: String?,
)

internal object ItunesEditorialCatalog {
    private val mbid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /**
     * The Search API returns collectionType=Album for EPs and singles as well.
     * Only a suffix explicitly present in Apple's own editorial display name
     * can qualify EP/Single at this stage. Unknown is NOT silently an Album,
     * nor is a MusicBrainz primary type allowed to control this label.
     */
    fun formatFromAppleTitle(title: String): ItunesEditorialReleaseFormat {
        val normalized = title.trim().lowercase(Locale.ROOT)
        return when {
            normalized.endsWith(" - ep") || normalized.endsWith(" – ep") ||
                normalized.endsWith(" — ep") -> ItunesEditorialReleaseFormat.EP
            normalized.endsWith(" - single") || normalized.endsWith(" – single") ||
                normalized.endsWith(" — single") -> ItunesEditorialReleaseFormat.SINGLE
            else -> ItunesEditorialReleaseFormat.UNCLASSIFIED
        }
    }

    /**
     * It is intentional that only Apple rows form the output. MB-only releases
     * can be browsed in an explicitly separate archive, never injected here.
     * Callers must pass ONLY independently verified links; title resemblance,
     * UPC alone and ISRC alone are insufficient for canonical MBID attachment.
     */
    fun assemble(
        storefront: String,
        appleRows: List<ItunesCatalogItem>,
        verifiedLinks: List<ItunesVerifiedReleaseGroupLink> = emptyList(),
    ): List<ItunesEditorialRelease> {
        val country = storefront.trim().lowercase(Locale.ROOT)
        if (!Regex("[a-z]{2}").matches(country)) return emptyList()
        val rows = appleRows.asSequence()
            .filter { it.storefront == country }
            .distinctBy { it.scopedId }
            .toList()

        val links = verifiedLinks.asSequence()
            .filter { it.appleScopedId.startsWith("itunes:$country:album:") }
            .filter { mbid.matches(it.musicBrainzReleaseGroupId.lowercase(Locale.ROOT)) }
            .distinctBy { it.appleScopedId }
            .associate { it.appleScopedId to it.musicBrainzReleaseGroupId.lowercase(Locale.ROOT) }

        val tracksByCollection = rows.asSequence()
            .filter { it.type == ItunesCatalogItemType.TRACK && it.collectionId != null }
            .groupBy { it.collectionId }
            .mapValues { (_, tracks) ->
                tracks.sortedWith(compareBy<ItunesCatalogItem> { it.discNumber ?: Int.MAX_VALUE }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy { it.id })
            }

        return rows.asSequence()
            .filter { it.type == ItunesCatalogItemType.ALBUM && it.title.isNotBlank() &&
                !it.artistName.isNullOrBlank() }
            .map { album ->
                ItunesEditorialRelease(
                    appleScopedId = album.scopedId,
                    appleCollectionId = album.id,
                    storefront = country,
                    title = album.title,
                    artistName = album.artistName!!,
                    artistId = album.artistId,
                    releaseDate = album.releaseDate,
                    genre = album.genre,
                    format = formatFromAppleTitle(album.title),
                    artworkUrl = album.artworkUrl,
                    trackCount = album.trackCount,
                    tracks = tracksByCollection[album.id].orEmpty(),
                    musicBrainzReleaseGroupId = links[album.scopedId],
                )
            }
            .sortedWith(compareByDescending<ItunesEditorialRelease> { it.releaseDate.orEmpty() }
                .thenBy { it.appleCollectionId })
            .toList()
    }
}
