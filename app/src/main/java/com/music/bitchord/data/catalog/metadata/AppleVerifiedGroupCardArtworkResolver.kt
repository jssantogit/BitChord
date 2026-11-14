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
    groupTitle: String? = null,
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
    val eligible = page.items.filter(::isCuratedMusicBrainzRelease)
    if (eligible.size == 1) return eligible.single().musicBrainzId
    // When multiple Official audio Releases exist, a selected exact-title
    // edition can represent the default Group cover ONLY if all competing
    // Official editions declare a clear alternate package. Do not guess
    // between two exact-name regional editions, or an unspecified "(Japan)".
    val title = groupTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val primary = eligible.filter { it.title.trim().equals(title, ignoreCase = true) }
        .singleOrNull() ?: return null
    val alternate = Regex(
        """(?i)\b(?:deluxe|version|ver\.?|limited|collector|reissue|remaster|remix|instrumental|karaoke|live|vinyl|photobook|jewel\s*case|photocard|digipack|nemo|poca|platform|kit\s+album|sped\s+up|slowed|signed)\b""",
    )
    if (eligible.any { edition ->
        edition.musicBrainzId != primary.musicBrainzId &&
            (!edition.title.startsWith(title, ignoreCase = true) ||
                !alternate.containsMatchIn(edition.title.substring(title.length)))
    }) return null
    return primary.musicBrainzId
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
        groupTitle: String? = null,
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
        val editionId = uniqueAppleGroupCardEdition(groupMbid, page, groupTitle)
            ?: return null
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
