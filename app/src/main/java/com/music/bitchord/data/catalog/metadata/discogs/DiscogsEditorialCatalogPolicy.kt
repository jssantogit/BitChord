package com.music.bitchord.data.catalog.metadata.discogs

import java.text.Normalizer
import java.util.Locale

/**
 * A no-link search result is never a canonical MusicBrainz identity.
 * This matcher consumes real Discogs Release details (not search thumbnails).
 * Only FULL title/credited artists/track order plus an identical validated
 * barcode and non-contradictory year may verify a cross-provider edition.
 */
internal sealed interface DiscogsUnlinkedMatch {
    data class Verified(val record: DiscogsRecord) : DiscogsUnlinkedMatch
    data class Ambiguous(val records: List<DiscogsRecord>) : DiscogsUnlinkedMatch
    data class Candidates(val records: List<DiscogsRecord>) : DiscogsUnlinkedMatch
    data object NoMatch : DiscogsUnlinkedMatch

    companion object {
        fun select(
            musicBrainzEdition: CanonicalEditionEvidence,
            candidates: List<DiscogsRecord>,
        ): DiscogsUnlinkedMatch {
            val unique = candidates.take(32).filter { it.link.kind == DiscogsKind.RELEASE }
                .distinctBy { it.link.id }
            val assessed = unique.map { it to DiscogsEditionMatcher.assessUnlinked(musicBrainzEdition, it) }
            val verified = assessed.filter { it.second == DiscogsEditionVerdict.VERIFIED }
                .map { it.first }
            return when {
                verified.size == 1 -> Verified(verified.single())
                verified.size > 1 -> Ambiguous(verified)
                else -> {
                    val pending = assessed.filter { it.second == DiscogsEditionVerdict.CANDIDATE }
                        .map { it.first }
                    if (pending.isEmpty()) NoMatch else Candidates(pending)
                }
            }
        }
    }
}

/** Source statement is verified only after a complete exact edition match and group membership. */
internal data class DiscogsGroupTypeProof(
    val releaseId: Long,
    val kind: DiscogsEditorialKind?,
    val verified: Boolean,
)

internal enum class DiscogsTypeSource {
    MUSICBRAINZ, DISCOGS_CORROBORATED, DISCOGS_FILL, DISCOGS_CORRECTION,
}

internal sealed interface DiscogsTypeResolution {
    data class Decided(
        val kind: DiscogsEditorialKind,
        val source: DiscogsTypeSource,
        val releaseIds: List<Long>,
    ) : DiscogsTypeResolution

    data class Disputed(
        val musicBrainzKind: DiscogsEditorialKind?,
        val discogsKinds: Set<DiscogsEditorialKind>,
    ) : DiscogsTypeResolution

    data object Unknown : DiscogsTypeResolution
}

/**
 * A derived DISPLAY classification: never mutate MusicBrainz ReleaseGroup,
 * local user tags, playlist membership or artwork caches.
 *
 * Distinct Discogs releases are not independent publishers. The two-edition
 * threshold only guards against an isolated mislabeled edition; any contrary
 * kind prevents automatic correction. Unknown or unsupported MB types are not
 * silently interpreted as albums.
 */
internal object DiscogsTypeConsensus {
    fun resolve(
        musicBrainzPrimaryType: String?,
        proofs: List<DiscogsGroupTypeProof>,
    ): DiscogsTypeResolution {
        val canonical = when (musicBrainzPrimaryType?.trim()?.lowercase(Locale.ROOT)) {
            "album" -> DiscogsEditorialKind.ALBUM
            "ep" -> DiscogsEditorialKind.EP
            "single" -> DiscogsEditorialKind.SINGLE
            else -> null
        }
        val verified = proofs.asSequence()
            .filter { it.verified && it.releaseId in 1..999_999_999_999L }
            .distinctBy { it.releaseId }
            .filter { it.kind in setOf(DiscogsEditorialKind.ALBUM,
                DiscogsEditorialKind.EP, DiscogsEditorialKind.SINGLE) }
            .toList()
        val kinds = verified.mapNotNull { it.kind }.toSet()
        if (kinds.size > 1) return DiscogsTypeResolution.Disputed(canonical, kinds)
        val external = kinds.singleOrNull()
        val ids = verified.map { it.releaseId }.sorted()
        if (external == null) {
            return canonical?.let {
                DiscogsTypeResolution.Decided(it, DiscogsTypeSource.MUSICBRAINZ, emptyList())
            } ?: DiscogsTypeResolution.Unknown
        }
        if (canonical == null) {
            return DiscogsTypeResolution.Decided(
                external, DiscogsTypeSource.DISCOGS_FILL, ids,
            )
        }
        if (canonical == external) {
            return DiscogsTypeResolution.Decided(
                canonical, DiscogsTypeSource.DISCOGS_CORROBORATED, ids,
            )
        }
        return if (ids.size >= 2) {
            DiscogsTypeResolution.Decided(
                external, DiscogsTypeSource.DISCOGS_CORRECTION, ids,
            )
        } else DiscogsTypeResolution.Disputed(canonical, kinds)
    }
}

/**
 * Source-only items are safe to DISPLAY even without MusicBrainz identifiers.
 * They are not proof a MusicBrainz release is absent until the caller completed
 * its relevant lookup; they never become fictitious MBIDs or playable assets.
 */
internal data class DiscogsPublicEditionSnapshot(
    val record: DiscogsRecord,
    val fetchedAtMillis: Long,
)

internal enum class DiscogsEditorialShelf { ALBUMS, EPS, SINGLES }

internal data class DiscogsShadowRelease(
    val key: String,
    val discogsReleaseId: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val kind: DiscogsEditorialKind,
    val shelf: DiscogsEditorialShelf,
    val attributionUrl: String,
) {
    val musicBrainzId: String? get() = null
    val canPlay: Boolean get() = false
}

internal object DiscogsShadowCatalog {
    // Normalization is comparison-only. An exact credited artist prefix is
    // common in Discogs titles; never strip arbitrary dash-separated text.
    private val whitespace = Regex("\\s+")

    private fun normalizedTitle(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
            .lowercase(Locale.ROOT).replace(whitespace, " ")

    private fun comparisonTitle(raw: String, artist: String): String {
        val title = normalizedTitle(raw)
        val prefix = "${normalizedTitle(artist)} - "
        return if (title.startsWith(prefix)) title.removePrefix(prefix) else title
    }

    fun select(
        observations: List<DiscogsPublicEditionSnapshot>,
        expectedArtist: String,
        mbCatalogChecked: Boolean,
        nowMillis: Long,
        linkedReleaseIds: Set<Long> = emptySet(),
        knownMusicBrainzTitles: Set<String> = emptySet(),
    ): List<DiscogsShadowRelease> {
        if (!mbCatalogChecked || expectedArtist.isBlank() || nowMillis < 0L) return emptyList()
        val artist = normalizedTitle(expectedArtist)
        val knownTitles = knownMusicBrainzTitles.mapTo(HashSet()) {
            normalizedTitle(it)
        }
        val eligible = observations.take(80).mapNotNull { item ->
            val release = item.record
            if (item.fetchedAtMillis !in 0..nowMillis ||
                nowMillis - item.fetchedAtMillis >= DiscogsObservation.TTL_MILLIS ||
                release.link.kind != DiscogsKind.RELEASE ||
                release.link.id in linkedReleaseIds ||
                release.unofficial || release.title.isBlank() ||
                release.artistNames.size != 1 ||
                normalizedTitle(release.artistNames.single()) != artist ||
                comparisonTitle(release.title, expectedArtist) in knownTitles ||
                release.tracklist.isEmpty() ||
                release.year == null ||
                release.labels.isEmpty()
            ) return@mapNotNull null
            val kind = discogsEditorialKind(release.formats) ?: return@mapNotNull null
            val shelf = when (kind) {
                DiscogsEditorialKind.ALBUM -> DiscogsEditorialShelf.ALBUMS
                DiscogsEditorialKind.EP, DiscogsEditorialKind.MINI_ALBUM ->
                    DiscogsEditorialShelf.EPS
                DiscogsEditorialKind.SINGLE, DiscogsEditorialKind.MAXI_SINGLE ->
                    DiscogsEditorialShelf.SINGLES
                DiscogsEditorialKind.MIXTAPE -> return@mapNotNull null
            }
            val identity = release.masterId?.takeIf { it in 1..999_999_999_999L }
                ?.let { "discogs:master:$it" }
                ?: "discogs:release:${release.link.id}"
            DiscogsShadowRelease(
                key = identity,
                discogsReleaseId = release.link.id,
                title = release.title.trim().take(240),
                artist = release.artistNames.single().trim(),
                year = release.year,
                kind = kind,
                shelf = shelf,
                attributionUrl = release.attributionUrl,
            )
        }
        // A Master containing conflicting types or titles is not one safely
        // classified item. Prefer omitting it to arbitrarily selecting a version.
        return eligible.groupBy { it.key }.values.mapNotNull { variants ->
            if (variants.map { it.shelf }.distinct().size > 1 ||
                variants.map { comparisonTitle(it.title, expectedArtist) }.distinct().size > 1
            ) null
            else variants.minWithOrNull(compareBy<DiscogsShadowRelease> { it.year }
                .thenBy { it.discogsReleaseId })
        }.sortedWith(compareByDescending<DiscogsShadowRelease> { it.year }
            .thenBy { it.title.lowercase(Locale.ROOT) }
            .thenBy { it.key }).take(32)
    }

    fun shelves(releases: List<DiscogsShadowRelease>):
        Map<DiscogsEditorialShelf, List<DiscogsShadowRelease>> =
        DiscogsEditorialShelf.entries.associateWith { shelf ->
            releases.filter { it.shelf == shelf }.sortedWith(
                compareByDescending<DiscogsShadowRelease> { it.year }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
                    .thenBy { it.key },
            )
        }
}
