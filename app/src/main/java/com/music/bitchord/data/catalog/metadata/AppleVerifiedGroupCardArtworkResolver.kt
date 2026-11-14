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
 * artwork only when a complete group browse proves exactly ONE eligible
 * Official audio MB edition. Additional bootleg/promo editions are not
 * candidates, and the first-ranked edition is never sufficient evidence.
 *
 * The returned MB Release is only a candidate. The independent UPC + complete
 * tracklist + competing-edition check must still certify the Apple product.
 * No title-based Apple search, synthesized artwork sizes or Apple playback.
 */
internal fun uniqueAppleGroupCardEdition(
    requestedGroupMbid: String,
    page: MusicBrainzCatalogPage<MusicBrainzReleaseHit>,
): String? {
    // A partial browse can hide another competing Official edition. Fail
    // closed on incomplete, malformed, duplicated or huge result sets.
    if (page.offset != 0 || page.total !in 1..100 ||
        page.consumedCount != page.total || page.items.size != page.total ||
        page.items.map { it.musicBrainzId.lowercase() }.distinct().size != page.total
    ) return null
    if (page.items.any {
        !it.releaseGroupMbid.equals(requestedGroupMbid, ignoreCase = true) ||
            it.artistDisplay.isNullOrBlank()
    }) return null
    return page.items.filter(::isCuratedMusicBrainzRelease)
        .singleOrNull()?.musicBrainzId
}

internal class AppleVerifiedGroupCardArtworkResolver(
    private val groups: AppleGroupCardEditionSource = AppleGroupCardEditionSource { group ->
        MusicBrainzCatalogGraph().browseGroupReleases(group, limit = 100)
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
