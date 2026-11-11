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
    /** Discogs Master groups variants; variants do not represent separate votes. */
    val masterId: Long? = null,
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
        // Multiple verified observations of the same Discogs Release may
        // disagree due to stale source snapshots or inconsistent Master links.
        // Deduplicating first would discard that contradiction and could
        // falsely manufacture two independent votes for a type correction.
        val eligible = proofs.filter {
            it.verified && it.releaseId in 1..999_999_999_999L
        }
        val byReleaseId = eligible.groupBy { it.releaseId }
        val contradictoryRelease = byReleaseId.values.any { observations ->
            observations.mapNotNull { it.kind }.distinct().size > 1 ||
                observations.mapNotNull { proof ->
                    proof.masterId?.takeIf { it in 1..999_999_999_999L }
                }.distinct().size > 1
        }
        if (contradictoryRelease) {
            return DiscogsTypeResolution.Disputed(
                canonical, eligible.mapNotNull { it.kind }.toSet(),
            )
        }
        // If one duplicate lacks a Master but another declares a valid one,
        // retain the known grouping: otherwise we may count two editions
        // from the same Master as independent assertions.
        val verified = byReleaseId.values.mapNotNull { observations ->
            observations.firstOrNull { proof ->
                proof.masterId in 1..999_999_999_999L
            } ?: observations.firstOrNull()
        }.filter { it.kind in setOf(DiscogsEditorialKind.ALBUM,
            DiscogsEditorialKind.EP, DiscogsEditorialKind.SINGLE) }
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
        // A Master groups versions of the same release, not independent
        // editorial assertions. Count all its physical editions as ONE vote.
        // Without a valid Master ID, only the exact edition ID can be grouped.
        val independentEvidence = verified.map { proof ->
            proof.masterId?.takeIf { it in 1..999_999_999_999L }
                ?.let { "master:$it" } ?: "release:${proof.releaseId}"
        }.distinct().size
        return if (independentEvidence >= 2) {
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

    // Comparison-only typography and colon spacing; do not infer translations
    // or change exact edition identity matching.
    private val colonSpacing = Regex("""\s*:\s*""")
    private val lexicalTokens = Regex("""[\p{L}\p{N}]+""")

    // MusicBrainz itself sometimes writes the second-script title as
    // "合 (HOP)" or "ジュエリー (Jewelry)". This is a source-asserted alias,
    // not an inferred translation. Never treat "(Live)" or "(Remix)" as one.
    private val explicitBilingualAlias = Regex(
        """^(.+?)\s+\(([a-z][a-z0-9 &'’!.\-]{2,65})\)$""",
    )
    private val misleadingAlias = setOf(
        "live", "remix", "demo", "acoustic", "instrumental",
        "version", "english", "japanese", "korean", "explicit", "clean",
    )

    // Only compare the SAME explicit native title and Latin alias, where MB
    // writes "誓い (CHIKAI)" and Discogs writes "誓い = Chikai". No guesses.
    private val explicitBilingualPair = Regex(
        """^(.+?)\s+(?:\(([a-z][a-z0-9 &'’!.\-]{2,65})\)|=\s*([a-z][a-z0-9 &'’!.\-]{2,65}))$""",
    )

    private fun bilingualPair(title: String): Pair<String, String>? {
        val found = explicitBilingualPair.matchEntire(title) ?: return null
        val native = found.groupValues[1].trim()
        val alias = (found.groupValues[2].ifBlank { found.groupValues[3] }).trim()
        if (native.none {
                it in '가'..'힣' || it in 'ぁ'..'ヿ' || it in '一'..'鿿'
            } || alias.count { it in 'a'..'z' } < 3 ||
            alias in misleadingAlias
        ) return null
        return native to alias
    }

    private fun bilingualAliases(title: String): Set<String> {
        val parsed = explicitBilingualAlias.matchEntire(title) ?: return emptySet()
        val original = parsed.groupValues[1]
        if (original.none {
                it in '가'..'힣' || it in 'ぁ'..'ヿ' || it in '一'..'鿿'
            }) return emptySet()
        val alias = parsed.groupValues[2].trim()
        if (alias.count { it in 'a'..'z' } < 3 ||
            alias in misleadingAlias
        ) return emptySet()
        return setOf(alias)
    }

    /** Same ordered words, different separators; never a fuzzy title match. */
    private fun lexicalSignature(value: String): List<String> =
        lexicalTokens.findAll(value).map { it.value }.toList()

    private fun normalizedTitle(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
            .lowercase(Locale.ROOT)
            .replace("’", "'").replace("‘", "'")
            .replace(whitespace, " ")
            .replace(colonSpacing, ": ")

    private fun comparisonTitle(raw: String, artist: String): String {
        val title = normalizedTitle(raw)
        val prefix = "${normalizedTitle(artist)} - "
        return if (title.startsWith(prefix)) title.removePrefix(prefix) else title
    }

    // A version/edition suffix identifies a physical variant of the same
    // grouping title for absence checks only. Never use this relaxed check
    // for exact Release identity, playback, or Discogs verification.
    private val editionSuffix = Regex("""\s*[\(\[]([^\(\)\[\]]{1,72})[\)\]]$""")
    private val versionTag = Regex(
        """(?:\bversion\b|\bedition\b|\bver\.?(?=\s|$)|\bexplicit\b|\bclean\b)""",
    )
    private val distinctArrangement = Regex(
        """\b(?:remix|live|acoustic|instrumental|sped\s+up|slowed)\b""",
    )

    private fun isKnownGroupTitle(
        title: String,
        creditedArtist: String,
        known: Set<String>,
        knownSignatures: Set<List<String>>,
        explicitAliases: Set<String>,
        explicitPairs: Set<Pair<String, String>>,
    ): Boolean {
        val normalized = comparisonTitle(title, creditedArtist)
        val pair = bilingualPair(normalized)
        if (normalized in known || normalized in explicitAliases ||
            (pair != null && pair in explicitPairs)
        ) return true
        // Require at least three whole Unicode word tokens. "Love Me" and
        // "Love-Me" are too short to merge; subtitles/remixes add new tokens.
        val signature = lexicalSignature(normalized)
        if (signature.size >= 3 && signature in knownSignatures) return true
        val qualifier = editionSuffix.find(normalized) ?: return false
        val suffix = qualifier.groupValues[1]
        if (!versionTag.containsMatchIn(suffix) ||
            distinctArrangement.containsMatchIn(suffix)
        ) return false
        return normalized.removeRange(qualifier.range).trim() in known
    }

    /**
     * A DVD/Blu-ray containing album tracks must not become a music album
     * simply because its Discogs format description also says "Album".
     * Keep mixed audio+video products only when an audio medium is explicit.
     */
    private fun isVideoOnlyFormat(formats: List<String>): Boolean {
        val names = formats.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        val video = setOf(
            "dvd", "dvd-video", "blu-ray", "vhs", "laserdisc", "video",
            "vcd", "video cd",
        )
        val audio = setOf(
            "cd", "vinyl", "file", "digital media", "cassette", "sacd",
            "shellac", "minidisc", "8-track cartridge", "blu-ray audio",
        )
        return names.any { it in video } && names.none { it in audio }
    }

    // An already-known one-track digital Single can be written in Discogs
    // as "Fashion / ファッション" while the complete MusicBrainz artist browse
    // names the release group "Fashion". This is NOT edition verification
    // or a claim about the translation: it only blocks a *source-only*
    // assertion when the same one-track title visibly contains the exact
    // existing Latin group name plus a strictly Japanese second script.
    // Keep physical formats, multiple tracks, remixes and unknown titles
    // outside this narrowly scoped non-absence guard.
    private val digitalSingleSlash = Regex(
        """^([a-z][a-z0-9 &'!.\-]{2,90}) / ([ぁ-ヿ一-鿿]{3,60})$""",
    )

    private fun mayAlreadyBeKnownBilingualDigitalSingle(
        record: DiscogsRecord,
        creditedArtist: String,
        knownNormalizedTitles: Set<String>,
    ): Boolean {
        if (record.tracklist.size != 1 ||
            record.formats.none { it.trim().equals("File", ignoreCase = true) } ||
            discogsEditorialKind(record.formats) != DiscogsEditorialKind.SINGLE
        ) return false
        val title = comparisonTitle(record.title, creditedArtist)
        val pair = digitalSingleSlash.matchEntire(title) ?: return false
        if (pair.groupValues[1] !in knownNormalizedTitles) return false
        // A second song/arrangement in the medium is not a known single.
        // Do not guess equivalence between separately written track names.
        return normalizedTitle(record.tracklist.single().second) == title
    }

    /** A precomputed predicate shared between the index and hydrated edition. */
    internal fun knownTitlePredicate(
        expectedArtist: String,
        knownMusicBrainzTitles: Set<String>,
    ): (String) -> Boolean {
        val known = knownMusicBrainzTitles.mapTo(HashSet()) { normalizedTitle(it) }
        val signatures = known.mapTo(HashSet()) { lexicalSignature(it) }
            .filterTo(HashSet()) { it.size >= 3 }
        val aliases = known.flatMapTo(HashSet()) { bilingualAliases(it) }
        val pairs = known.mapNotNullTo(HashSet()) { bilingualPair(it) }
        return { title ->
            isKnownGroupTitle(title, expectedArtist, known, signatures, aliases, pairs)
        }
    }

    fun select(
        observations: List<DiscogsPublicEditionSnapshot>,
        expectedArtist: String,
        mbCatalogChecked: Boolean,
        nowMillis: Long,
        linkedReleaseIds: Set<Long> = emptySet(),
        knownMusicBrainzTitles: Set<String> = emptySet(),
        /** Exact provider ID supplied only by MusicBrainz Artist relationship. */
        expectedDiscogsArtistId: Long? = null,
    ): List<DiscogsShadowRelease> {
        if (!mbCatalogChecked || expectedArtist.isBlank() || nowMillis < 0L) return emptyList()
        val artist = normalizedTitle(expectedArtist)
        val knownTitle = knownTitlePredicate(expectedArtist, knownMusicBrainzTitles)
        val knownNormalizedTitles = knownMusicBrainzTitles.mapTo(HashSet()) {
            normalizedTitle(it)
        }
        val eligible = observations.take(80).mapNotNull { item ->
            val release = item.record
            val artistMatches = if (expectedDiscogsArtistId != null) {
                expectedDiscogsArtistId in 1..999_999_999_999L &&
                    release.artistIds.size == 1 &&
                    release.artistIds.single() == expectedDiscogsArtistId
            } else {
                // Authenticated discovery without an Artist relationship still
                // requires exact names, never heuristic suffix removal.
                normalizedTitle(release.artistNames.singleOrNull().orEmpty()) == artist
            }
            if (item.fetchedAtMillis !in 0..nowMillis ||
                nowMillis - item.fetchedAtMillis >= DiscogsObservation.TTL_MILLIS ||
                release.link.kind != DiscogsKind.RELEASE ||
                release.link.id in linkedReleaseIds ||
                release.unofficial || release.title.isBlank() ||
                isVideoOnlyFormat(release.formats) ||
                release.artistNames.size != 1 || !artistMatches ||
                knownTitle(release.title) ||
                mayAlreadyBeKnownBilingualDigitalSingle(
                    release, expectedArtist, knownNormalizedTitles,
                ) ||
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
                artist = if (expectedDiscogsArtistId != null) expectedArtist.trim()
                    else release.artistNames.single().trim(),
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
