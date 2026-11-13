package com.music.bitchord.data.catalog.metadata

import java.util.Locale

/**
 * A displayed Apple release retains its own ID/title/artwork/tracklist.
 * External editorial facts may only complete its displayed Album/EP/Single
 * classification. They never create or delete releases, rewrite Apple fields,
 * attach unverified MBIDs or imply that a catalog row is playable.
 *
 * A verified relation is produced by the dedicated crosswalk: this policy
 * deliberately does not infer one from UPC, ISRC, title or shared Master ID.
 */
internal enum class AppleEditorialFormatSource { APPLE, MUSICBRAINZ, DISCOGS }
internal enum class AppleEditorialFormatStatus { DECIDED, UNCLASSIFIED, CONFLICT }

internal data class AppleEditorialFormatEvidence(
    val source: AppleEditorialFormatSource,
    /** The exact storefront-scoped Apple collection identity. */
    val appleScopedId: String,
    /** MusicBrainz ReleaseGroup MBID or discogs:release:<numeric ID>. Never Master. */
    val sourceId: String,
    val format: ItunesEditorialReleaseFormat,
    /** Verified by external identity evidence, not a title-only comparison. */
    val exactMatchVerified: Boolean,
)

internal data class AppleEditorialFormatResolution(
    val format: ItunesEditorialReleaseFormat,
    val status: AppleEditorialFormatStatus,
    val sources: Set<AppleEditorialFormatSource>,
    /** Only disagreement evidence; Apple keeps precedence when explicit. */
    val disputedExternalFormats: Set<ItunesEditorialReleaseFormat>,
)

internal object AppleEditorialClassification {
    private val releaseGroupMbid =
        Regex("^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
    private val exactDiscogsRelease = Regex("^discogs:release:([1-9][0-9]*)$")
    private val maximumDiscogsId = 999_999_999_999L

    private fun verifiedFor(
        release: ItunesEditorialRelease,
        proof: AppleEditorialFormatEvidence,
    ): Boolean {
        if (!proof.exactMatchVerified || proof.appleScopedId != release.appleScopedId ||
            proof.format == ItunesEditorialReleaseFormat.UNCLASSIFIED ||
            proof.source == AppleEditorialFormatSource.APPLE
        ) return false
        return when (proof.source) {
            AppleEditorialFormatSource.MUSICBRAINZ -> {
                val id = release.musicBrainzReleaseGroupId
                    ?.lowercase(Locale.ROOT) ?: return false
                releaseGroupMbid.matches(id) &&
                    proof.sourceId.lowercase(Locale.ROOT) == id
            }
            AppleEditorialFormatSource.DISCOGS -> {
                val id = exactDiscogsRelease.matchEntire(proof.sourceId)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: return false
                id in 1..maximumDiscogsId
            }
            AppleEditorialFormatSource.APPLE -> false
        }
    }

    fun resolve(
        release: ItunesEditorialRelease,
        observations: List<AppleEditorialFormatEvidence>,
    ): AppleEditorialFormatResolution {
        // Prevent an accidentally mislabeled Apple record from accepting facts
        // for a different edition or a different country's storefront.
        if (release.appleCollectionId <= 0L ||
            !Regex("[a-z]{2}").matches(release.storefront) ||
            release.appleScopedId !=
                "itunes:${release.storefront}:album:${release.appleCollectionId}"
        ) return AppleEditorialFormatResolution(
            ItunesEditorialReleaseFormat.UNCLASSIFIED,
            AppleEditorialFormatStatus.UNCLASSIFIED, emptySet(), emptySet()
        )

        val facts = observations.filter { verifiedFor(release, it) }
            .distinctBy { Triple(it.source, it.sourceId.lowercase(Locale.ROOT), it.format) }
        val externalFormats = facts.map { it.format }.toSet()
        val conflictingSnapshots = facts.groupBy {
            it.source to it.sourceId.lowercase(Locale.ROOT)
        }.values.any { rows -> rows.map { it.format }.distinct().size > 1 }

        val apple = release.format
        if (apple != ItunesEditorialReleaseFormat.UNCLASSIFIED) {
            return AppleEditorialFormatResolution(
                format = apple,
                status = AppleEditorialFormatStatus.DECIDED,
                sources = setOf(AppleEditorialFormatSource.APPLE) +
                    facts.filter { it.format == apple }.map { it.source },
                disputedExternalFormats = externalFormats - apple,
            )
        }
        if (conflictingSnapshots || externalFormats.size > 1) {
            return AppleEditorialFormatResolution(
                format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
                status = AppleEditorialFormatStatus.CONFLICT,
                sources = facts.map { it.source }.toSet(),
                disputedExternalFormats = externalFormats,
            )
        }
        val only = externalFormats.singleOrNull()
        return if (only == null) {
            AppleEditorialFormatResolution(
                ItunesEditorialReleaseFormat.UNCLASSIFIED,
                AppleEditorialFormatStatus.UNCLASSIFIED, emptySet(), emptySet(),
            )
        } else {
            AppleEditorialFormatResolution(
                format = only,
                status = AppleEditorialFormatStatus.DECIDED,
                sources = facts.map { it.source }.toSet(),
                disputedExternalFormats = emptySet(),
            )
        }
    }
}
