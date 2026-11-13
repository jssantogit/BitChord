package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionPresentation
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialKind
import java.util.Locale

/**
 * Apple defines the visible commercial set, not the presence of MB/Discogs
 * evidence. The latter may only annotate an Apple release after crosswalks
 * are independently verified.
 *
 * Intentionally a pure Android data-layer read model: no Network, Room,
 * playback, generated Browse IDs, chart claims or UI coupling.
 */
internal data class AppleTriSourceEditorialEntry(
    val release: ItunesEditorialRelease,
    val match: AppleMusicBrainzEditionMatch,
    val classification: AppleEditorialFormatResolution,
    /** Nullable, fully source-attributed verified exact Discogs edition. */
    val discogsEdition: DiscogsEditionPresentation?,
)

internal object AppleTriSourceEditorialReconciler {
    private fun musicBrainzFormat(raw: String?): ItunesEditorialReleaseFormat = when (
        raw?.trim()?.lowercase(Locale.ROOT)
    ) {
        "album" -> ItunesEditorialReleaseFormat.ALBUM
        "ep" -> ItunesEditorialReleaseFormat.EP
        "single" -> ItunesEditorialReleaseFormat.SINGLE
        else -> ItunesEditorialReleaseFormat.UNCLASSIFIED
    }

    private fun discogsFormat(kind: DiscogsEditorialKind?): ItunesEditorialReleaseFormat =
        when (kind) {
            DiscogsEditorialKind.ALBUM -> ItunesEditorialReleaseFormat.ALBUM
            DiscogsEditorialKind.EP -> ItunesEditorialReleaseFormat.EP
            DiscogsEditorialKind.SINGLE -> ItunesEditorialReleaseFormat.SINGLE
            // Mini-album, Maxi-single and Mixtape are not interchangeable
            // without an explicit consumer editorial policy.
            else -> ItunesEditorialReleaseFormat.UNCLASSIFIED
        }

    fun reconcile(
        storefront: String,
        appleRows: List<ItunesCatalogItem>,
        mbEditions: List<MusicBrainzCatalogReleaseDetail> = emptyList(),
        mbGroups: List<MusicBrainzReleaseGroupHit> = emptyList(),
        discogsObservations: List<DiscogsEditionEnrichment> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): List<AppleTriSourceEditorialEntry> {
        val appleOnly = ItunesEditorialCatalog.assemble(storefront, appleRows)
        if (appleOnly.isEmpty()) return emptyList()

        val matches = appleOnly.associate { release ->
            release.appleScopedId to AppleMusicBrainzEditionMatcher.match(release, mbEditions)
        }

        // Even two perfectly matching Apple records may represent separate
        // store products (remaster, pressing, reissue). Do not attach the same
        // MB Release as identity to both in a single storefront.
        val claimedMbReleaseIds = matches.values
            .filterIsInstance<AppleMusicBrainzEditionMatch.Verified>()
            .groupBy { it.musicBrainzReleaseId }
        val disputed = claimedMbReleaseIds
            .filterValues { links -> links.map { it.appleScopedId }.distinct().size > 1 }
            .keys

        val safeMatches = matches.mapValues { (_, decision) ->
            if (decision is AppleMusicBrainzEditionMatch.Verified &&
                decision.musicBrainzReleaseId in disputed
            ) AppleMusicBrainzEditionMatch.Ambiguous(listOf(decision.musicBrainzReleaseId))
            else decision
        }
        val verified = safeMatches.values.filterIsInstance<AppleMusicBrainzEditionMatch.Verified>()

        val enriched = ItunesEditorialCatalog.assemble(
            storefront = storefront,
            appleRows = appleRows,
            verifiedLinks = verified.map { it.groupLink },
            verifiedReleaseLinks = verified.map { it.releaseLink },
            verifiedRecordingLinks = verified.flatMap { it.recordingLinks },
        )
        return enriched.map { apple ->
            val matched = safeMatches[apple.appleScopedId] ?: AppleMusicBrainzEditionMatch.NoMatch
            val group = (matched as? AppleMusicBrainzEditionMatch.Verified)
                ?.musicBrainzReleaseGroupId
            val edition = (matched as? AppleMusicBrainzEditionMatch.Verified)
                ?.musicBrainzReleaseId
            val facts = mutableListOf<AppleEditorialFormatEvidence>()

            if (group != null) {
                // Conflicting snapshots of the same group are disagreements,
                // not additional independent votes.
                mbGroups.filter { it.musicBrainzId.equals(group, ignoreCase = true) }
                    .map { musicBrainzFormat(it.primaryType) }
                    .filter { it != ItunesEditorialReleaseFormat.UNCLASSIFIED }
                    .distinct().forEach { type ->
                        facts += AppleEditorialFormatEvidence(
                            source = AppleEditorialFormatSource.MUSICBRAINZ,
                            appleScopedId = apple.appleScopedId,
                            sourceId = group,
                            format = type,
                            exactMatchVerified = true,
                        )
                    }
            }

            val discogs = if (edition == null) emptyList()
                else discogsObservations.mapNotNull { observation ->
                    val proof = observation as? DiscogsEditionEnrichment.Verified
                        ?: return@mapNotNull null
                    if (!proof.observation.canonicalMbid.equals(edition, ignoreCase = true))
                        return@mapNotNull null
                    val presented = DiscogsEditionPresentation.from(observation, nowMillis)
                        ?: return@mapNotNull null
                    presented
                }

            val validPresentations = discogs.distinct()
            if (edition != null) {
                validPresentations.forEach { presentation ->
                    val kind = discogsFormat(presentation.editorialKind)
                    if (kind != ItunesEditorialReleaseFormat.UNCLASSIFIED) {
                        // The attribution URL already comes from a verified
                        // Discogs Release, never a Master.
                        val releaseId = presentation.attributionUrl
                            .removePrefix("https://www.discogs.com/release/")
                            .toLongOrNull()
                        if (releaseId != null && releaseId in 1L..999_999_999_999L) {
                            facts += AppleEditorialFormatEvidence(
                                source = AppleEditorialFormatSource.DISCOGS,
                                appleScopedId = apple.appleScopedId,
                                sourceId = "discogs:release:$releaseId",
                                format = kind,
                                exactMatchVerified = true,
                            )
                        }
                    }
                }
            }

            AppleTriSourceEditorialEntry(
                release = apple,
                match = matched,
                classification = AppleEditorialClassification.resolve(apple, facts),
                // Do not display a randomly selected snapshot if the same
                // source edition has contradictory facts.
                discogsEdition = validPresentations.singleOrNull(),
            )
        }
    }
}
