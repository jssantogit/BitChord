package com.music.bitchord.data.catalog.metadata

import kotlinx.coroutines.CancellationException

internal fun interface AppleGroupCardEditionSource {
    suspend fun browse(groupMbid: String): MusicBrainzCatalogGraphResult<MusicBrainzCatalogPage<MusicBrainzReleaseHit>>
}

internal fun interface AppleGroupCardArtworkSource {
    suspend fun resolve(releaseMbid: String, storefront: String): AppleVerifiedEditionArtwork?
}

/**
 * A ReleaseGroup shelf is work-scoped, not edition-scoped. It may use Apple
 * artwork only when the complete group browse proves exactly ONE eligible
 * Official MB edition; a first-ranked edition is not enough.
 *
 * The returned MB Release is only a candidate. The independent UPC + complete
 * tracklist + competing-edition check must still certify the Apple product.
 * No title-based Apple search, synthesized artwork sizes or Apple playback.
 */
internal fun uniqueAppleGroupCardEdition(
    requestedGroupMbid: String,
    page: MusicBrainzCatalogPage<MusicBrainzReleaseHit>,
): String? {
    if (page.offset != 0 || page.total != 1 ||
        page.consumedCount != 1 || page.items.size != 1
    ) return null
    val edition = page.items.single()
    if (!edition.releaseGroupMbid.equals(requestedGroupMbid, ignoreCase = true) ||
        edition.artistDisplay.isNullOrBlank() ||
        !isCuratedMusicBrainzRelease(edition)
    ) return null
    return edition.musicBrainzId
}

internal class AppleVerifiedGroupCardArtworkResolver(
    private val groups: AppleGroupCardEditionSource = AppleGroupCardEditionSource { group ->
        MusicBrainzCatalogGraph().browseGroupReleases(group, limit = 2)
    },
    private val editions: AppleGroupCardArtworkSource = run {
        val resolver = AppleVerifiedEditionArtworkResolver()
        AppleGroupCardArtworkSource { release, storefront ->
            resolver.resolve(release, storefront)
        }
    },
) {
    suspend fun resolve(
        groupMbid: String,
        storefront: String = "br",
    ): AppleVerifiedEditionArtwork? {
        if (!Regex("^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
                .matches(groupMbid) ||
            !Regex("^[a-z]{2}$").matches(storefront)
        ) return null
        val page = try {
            when (val result = groups.browse(groupMbid)) {
                is MusicBrainzCatalogGraphResult.Success -> result.value
                is MusicBrainzCatalogGraphResult.Failure -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null } ?: return null
        val editionId = uniqueAppleGroupCardEdition(groupMbid, page) ?: return null
        val proof = try {
            editions.resolve(editionId, storefront)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        return verifiedAppleArtworkForDisplayedGroupEdition(
            proof, groupMbid, editionId,
        )
    }
}
