package com.music.bitchord.data.catalog.metadata.discogs

import java.text.Normalizer
import java.util.Locale

/**
 * Independent edition facts from MusicBrainz, *not* guessed from a display title.
 * An empty barcode or track list means unavailable, not a verified empty medium.
 */
internal data class CanonicalEditionEvidence(
    val musicBrainzReleaseId: String,
    val title: String,
    val creditedArtist: String?,
    val status: String?,
    val barcodes: List<String>,
    val trackTitles: List<String>,
    /** Exact edition country and year when asserted by MusicBrainz. */
    val country: String? = null,
    val releaseYear: Int? = null,
    /** Verbatim MB release artist-credit entries, never a split display string. */
    val creditedArtistNames: List<String> = emptyList(),
    /** Parent grouping identity, never interchangeable with this edition MBID. */
    val musicBrainzReleaseGroupId: String? = null,
    /** Exact-release medium formats already parsed from MusicBrainz media. */
    val editionFormats: List<String> = emptyList(),
    /** Direct label/catalog pair from this Release, not parsed display text. */
    val editionLabelCatalogPairs: List<Pair<String, String>> = emptyList(),
)

/** A Discogs link is an identity *anchor*. Matching fields are separate evidence. */
internal enum class DiscogsEditionVerdict {
    VERIFIED,
    CANDIDATE,
    CONFLICT,
}

internal object DiscogsEditionMatcher {
    private val mbid = Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
    private val spaces = Regex("\\s+")
    private val unicodeWords = Regex("""[\p{L}\p{N}]+""")
    private val recognizedIsoCountries = Locale.getISOCountries().mapTo(HashSet()) {
        it.lowercase(Locale.ROOT)
    }
    private val isoCountryByEnglishName = Locale.getISOCountries().associate { iso ->
        normalized(Locale.Builder().setRegion(iso).build()
            .getDisplayCountry(Locale.ENGLISH)) to iso.lowercase(Locale.ROOT)
    }
    private val countryAliases = mapOf(
        "uk" to "gb", "great britain" to "gb",
        "usa" to "us", "u.s.a." to "us", "united states of america" to "us",
        "republic of korea" to "kr", "korea, republic of" to "kr",
        "south korea" to "kr", "north korea" to "kp",
    )


    fun assess(
        canonical: CanonicalEditionEvidence,
        linked: DiscogsLink,
        record: DiscogsRecord,
        /** Only a typed MB Release -> Discogs Release URL, never search/Master. */
        explicitReleaseRelation: Boolean = false,
    ): DiscogsEditionVerdict {
        if (!mbid.matches(canonical.musicBrainzReleaseId) ||
            linked.kind != DiscogsKind.RELEASE ||
            record.link != linked
        ) return DiscogsEditionVerdict.CONFLICT

        val artist = canonical.creditedArtist?.takeIf(String::isNotBlank)
            ?: return DiscogsEditionVerdict.CANDIDATE
        if (record.artistNames.isEmpty()) return DiscogsEditionVerdict.CANDIDATE
        // A displayed credit such as "A & B" is not an individual Discogs
        // artist. Compare every structured MusicBrainz credit when available;
        // never heuristically split names on &, feat., commas or CJK text.
        val expected = canonical.creditedArtistNames.map(::normalized)
        val actual = record.artistNames.map(::normalized)
        val artistMatches = if (expected.isNotEmpty()) {
            expected.size == actual.size && expected.sorted() == actual.sorted()
        } else {
            // Display credit cannot prove a second collaborator. With no
            // structured MusicBrainz artist list, only a single exact
            // Discogs artist can be corroborated; extra artists are a conflict.
            actual.size == 1 && actual.single() == normalized(artist)
        }
        // A direct typed MB Release URL, corroborated by a validated barcode
        // and matching year, can tolerate punctuation-only differences.
        // Never give this exception to anonymous search or Master candidates.
        val mbBarcodes = canonical.barcodes.mapNotNull(::barcode).toSet()
        val externalBarcodes = record.barcodes.mapNotNull(::barcode).toSet()
        val bothHaveBarcodes = mbBarcodes.isNotEmpty() && externalBarcodes.isNotEmpty()
        val barcodeMatches = bothHaveBarcodes && mbBarcodes.any { it in externalBarcodes }
        val linkedFormattingAllowed = explicitReleaseRelation && barcodeMatches &&
            canonical.releaseYear != null && canonical.releaseYear == record.year &&
            record.labels.isNotEmpty()
        if (!artistMatches ||
            !sameEditionTitle(canonical.title, record.title, artist,
                canonical.creditedArtistNames, linkedFormattingAllowed)
        ) return DiscogsEditionVerdict.CONFLICT

        // The same recording sequence can be issued in several countries.
        // Compare only recognized country codes/names; "Worldwide", "Europe"
        // and other aggregate regions are not edition-country proofs.
        val mbCountry = comparableCountry(canonical.country)
        val discogsCountry = comparableCountry(record.country)
        if (mbCountry != null && discogsCountry != null &&
            mbCountry != discogsCountry
        ) return DiscogsEditionVerdict.CONFLICT

        // Track counts and even valid barcodes can be shared between a
        // digital issue, CD, vinyl and a bundled mixed-format release.
        // Compare only explicit, recognized media families on both sources.
        // Unknown source formats cannot be used to invent a contradiction.
        val mbMedia = canonical.editionFormats.mapNotNull(::mediaFamily).toSet()
        val discogsMedia = record.formats.mapNotNull(::mediaFamily).toSet()
        if (mbMedia.isNotEmpty() && discogsMedia.isNotEmpty()) {
            if (mbMedia.intersect(discogsMedia).isEmpty()) {
                return DiscogsEditionVerdict.CONFLICT
            }
            // The Discogs Release lists additional physical media absent
            // from the exact MB edition (or vice versa). It may be related,
            // but is not proved to be the same packaging/pressing.
            if (mbMedia != discogsMedia) {
                return DiscogsEditionVerdict.CANDIDATE
            }
        }

        // Two editions can have identical barcode, artist and tracklist
        // while their own label declares different catalog/packaging IDs.
        // Only compare the SAME literal label after whitespace/case folding;
        // unknown labels or placeholder numbers must not invent a conflict.
        // Treat a contradictory pressing as Candidate even for a typed direct
        // MB Release link; never rewrite either provider or claim uniqueness.
        if (hasKnownLabelCatalogMismatch(
                canonical.editionLabelCatalogPairs, record.labels,
            )) return DiscogsEditionVerdict.CANDIDATE

        // Unofficial *edition* observations are not claims about the entire RG.
        // Never validate an officially classified MB edition against one.
        if (record.unofficial) {
            return if (canonical.status.equals("Official", ignoreCase = true)) {
                DiscogsEditionVerdict.CONFLICT
            } else {
                DiscogsEditionVerdict.CANDIDATE
            }
        }

        if (bothHaveBarcodes && !barcodeMatches) return DiscogsEditionVerdict.CONFLICT

        val mbTracks = canonical.trackTitles.map(::normalized)
        val externalTracks = record.tracklist.map { normalized(it.second) }
        val bothHaveTracks = mbTracks.isNotEmpty() && externalTracks.isNotEmpty()
        val trackMatches = bothHaveTracks && mbTracks == externalTracks
        // At most two punctuation-only differences in an otherwise identical
        // ordered tracklist (five or more tracks) are presentation variants,
        // not evidence of remixes, translations or different recordings.
        val linkedPunctuationMatch = !trackMatches && linkedFormattingAllowed &&
            mbTracks.size >= 5 && mbTracks.size == externalTracks.size &&
            mbTracks.indices.count { mbTracks[it] == externalTracks[it] } >=
                mbTracks.size - 2 &&
            mbTracks.indices.all { i ->
                mbTracks[i] == externalTracks[i] ||
                    sameOrderedWords(mbTracks[i], externalTracks[i], minimumWords = 2)
            }
        // A genuine typed MB Release -> Discogs Release relationship can carry
        // translated track names. This is deliberately NOT available to fuzzy
        // search, Master associations, or unknown-country/year/barcode editions.
        val linkedCrossScriptMatch = !trackMatches && explicitReleaseRelation &&
            barcodeMatches && canonical.releaseYear != null &&
            canonical.releaseYear == record.year &&
            mbCountry != null && mbCountry == discogsCountry &&
            record.labels.isNotEmpty() &&
            conservativeCrossScriptTracks(mbTracks, externalTracks)
        // A conflicting tracklist can signal a pressing, remix or bonus edition.
        if (bothHaveTracks && !trackMatches && !linkedCrossScriptMatch &&
            !linkedPunctuationMatch
        ) return DiscogsEditionVerdict.CANDIDATE

        // A contradictory edition year prevents VERIFIED status even if
        // the tracklist matches; a different pressing may carry the same songs.
        if (canonical.releaseYear != null && record.year != null &&
            canonical.releaseYear != record.year
        ) return DiscogsEditionVerdict.CANDIDATE

        // Other internal consumers can call this matcher directly, without
        // passing through the coordinator's Official-only graph gate.
        // Unknown, promo and bootleg MusicBrainz status must never be VERIFIED.
        if (!canonical.status.equals("Official", ignoreCase = true)) {
            return DiscogsEditionVerdict.CANDIDATE
        }

        // Barcodes are shared by variants or entered incorrectly. A curated
        // MusicBrainz URL association plus artist/title and matching barcode
        // is not enough to prove the exact edition without track evidence.
        return if (trackMatches || linkedCrossScriptMatch || linkedPunctuationMatch) {
            DiscogsEditionVerdict.VERIFIED
        } else {
            DiscogsEditionVerdict.CANDIDATE
        }
    }

    private fun hasKnownLabelCatalogMismatch(
        musicBrainz: List<Pair<String, String>>,
        discogs: List<Pair<String, String>>,
    ): Boolean {
        val placeholders = setOf(
            "none", "n/a", "na", "unknown", "?", "-", "not on label",
            "no label", "tba", "no catalog number", "[none]",
        )
        fun exactPairs(source: List<Pair<String, String>>): Map<String, Set<String>> {
            return source.take(16).mapNotNull { (label, number) ->
                val name = normalized(label).takeIf {
                    it.isNotEmpty() && it.length <= 180 &&
                        it !in placeholders && !it.startsWith("not on label (")
                } ?: return@mapNotNull null
                val catalog = normalized(number).takeIf {
                    it.isNotEmpty() && it.length <= 100 && it !in placeholders
                } ?: return@mapNotNull null
                name to catalog
            }.groupBy({ it.first }, { it.second })
                .mapValues { (_, numbers) -> numbers.toSet() }
        }
        val known = exactPairs(musicBrainz)
        val external = exactPairs(discogs)
        return known.keys.intersect(external.keys).any { name ->
            known.getValue(name).intersect(external.getValue(name)).isEmpty()
        }
    }

    private fun conservativeCrossScriptTracks(
        canonical: List<String>,
        external: List<String>,
    ): Boolean {
        // Fewer than five positions or less than half of the tracks matching
        // exactly cannot prove an edition despite a linked barcode.
        if (canonical.size < 5 || canonical.size != external.size) return false
        val exact = canonical.indices.count { canonical[it] == external[it] }
        if (exact < maxOf(3, (canonical.size + 1) / 2) ||
            exact == canonical.size
        ) return false
        fun hangulOnly(text: String) =
            text.any { it in '가'..'힣' } &&
                text.none { it in 'a'..'z' || it in 'A'..'Z' }
        fun latinOnly(text: String) =
            text.any { it in 'a'..'z' || it in 'A'..'Z' } &&
                text.none { it in '가'..'힣' }
        return canonical.indices.all { index ->
            val a = canonical[index]
            val b = external[index]
            a == b || (hangulOnly(a) && latinOnly(b)) ||
                (latinOnly(a) && hangulOnly(b))
        }
    }

    /**
     * Exact evidence for a Discogs Release found without a MusicBrainz URL.
     * A shared UPC by itself is not an identity. Require equal complete track
     * order, credited artists/title, a checked barcode and compatible year.
     * Candidates never rewrite MusicBrainz identifiers or canonical type.
     */
    fun assessUnlinked(
        canonical: CanonicalEditionEvidence,
        candidate: DiscogsRecord,
    ): DiscogsEditionVerdict {
        val linkedAssessment = assess(canonical, candidate.link, candidate)
        if (linkedAssessment != DiscogsEditionVerdict.VERIFIED) return linkedAssessment
        if (canonical.releaseYear == null || candidate.year == null ||
            canonical.releaseYear != candidate.year
        ) return DiscogsEditionVerdict.CANDIDATE
        val expected = canonical.barcodes.mapNotNull(::barcode).toSet()
        val observed = candidate.barcodes.mapNotNull(::barcode).toSet()
        if (expected.isEmpty() || observed.isEmpty()) return DiscogsEditionVerdict.CANDIDATE
        return if (expected.any { it in observed }) DiscogsEditionVerdict.VERIFIED
        else DiscogsEditionVerdict.CONFLICT
    }

    /**
     * Discogs' release.title commonly contains a leading "Artist - " while
     * MusicBrainz release.title does not. Strip only a *known credited artist*
     * prefix; creditedArtist is checked against Discogs artistNames above.
     * This is comparison-only; it never rewrites either source title.
     */
    private fun sameEditionTitle(
        album: String,
        discogsTitle: String,
        artistCredit: String?,
        creditedArtistNames: List<String>,
        allowLinkedPunctuation: Boolean = false,
    ): Boolean {
        val title = normalized(album)
        val observed = normalized(discogsTitle)
        if (observed == title) return true
        val artist = artistCredit?.takeIf(String::isNotBlank) ?: return false
        val prefixes = mutableListOf(normalized(artist))
        if (creditedArtistNames.size > 1) {
            // Discogs titles often render the same structured credits with a
            // comma instead of MusicBrainz's joinphrase. This is safe only
            // after the full artist list was independently checked above.
            prefixes += normalized(creditedArtistNames.joinToString(", "))
            prefixes += normalized(creditedArtistNames.joinToString(" & "))
        }
        val candidates = listOf(observed) + prefixes.mapNotNull { prefix ->
            observed.removePrefix("$prefix - ").takeIf { it != observed }
        }
        if (title in candidates) return true
        return allowLinkedPunctuation && candidates.any {
            sameOrderedWords(title, it, minimumWords = 3)
        }
    }

    private fun sameOrderedWords(a: String, b: String, minimumWords: Int): Boolean {
        fun words(s: String) =
            unicodeWords.findAll(Normalizer.normalize(s, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)).map { it.value }.toList()
        val first = words(a)
        return first.size >= minimumWords && first == words(b)
    }

    private fun normalized(s: String): String =
        s.trim().replace(spaces, " ").lowercase(Locale.ROOT)

    private fun comparableCountry(raw: String?): String? {
        val country = raw?.let(::normalized)?.takeIf(String::isNotBlank) ?: return null
        if (country in setOf("xw", "xe", "worldwide", "europe", "unknown",
                "not known", "[worldwide]")) return null
        return when {
            country in recognizedIsoCountries -> country
            country in countryAliases -> countryAliases[country]
            else -> isoCountryByEnglishName[country]
        }
    }

    /** Explicit medium evidence, NOT generic Album/EP/Single type descriptors. */
    private fun mediaFamily(raw: String): String? {
        val token = normalized(raw)
        return when {
            token in setOf("digital media", "file", "files", "download",
                "digital download") -> "digital"
            token == "cd" -> "cd"
            token in setOf("cdr", "cd-r") -> "cdr"
            token == "vinyl" || token == "lp" ||
                Regex("""^(7|10|12)["”]\s*vinyl$""").matches(token) -> "vinyl"
            token == "cassette" -> "cassette"
            token == "sacd" -> "sacd"
            token == "minidisc" -> "minidisc"
            token in setOf("dvd", "dvd-video", "dvd-r") -> "dvd"
            token in setOf("blu-ray", "blu-ray audio") -> "blu-ray"
            token == "vhs" -> "vhs"
            else -> null
        }
    }

    /** Keep leading zeroes. A barcode only corroborates a track- and artist-scoped link. */
    private fun barcode(raw: String): String? {
        val digits = raw.filterNot { it == ' ' || it == '-' }
        if (digits.length !in setOf(8, 12, 13, 14) ||
            digits.any { !it.isDigit() || it !in '0'..'9' }
        ) return null
        val expected = digits.last().digitToInt()
        val checkSum = digits.dropLast(1).reversed()
            .mapIndexed { index, char ->
                char.digitToInt() * if (index % 2 == 0) 3 else 1
            }.sum()
        return digits.takeIf { (10 - (checkSum % 10)) % 10 == expected }
    }
}
