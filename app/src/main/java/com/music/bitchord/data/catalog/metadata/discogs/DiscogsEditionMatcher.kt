package com.music.bitchord.data.catalog.metadata.discogs

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

    fun assess(
        canonical: CanonicalEditionEvidence,
        linked: DiscogsLink,
        record: DiscogsRecord,
    ): DiscogsEditionVerdict {
        if (!mbid.matches(canonical.musicBrainzReleaseId) ||
            linked.kind != DiscogsKind.RELEASE ||
            record.link != linked ||
            !sameEditionTitle(canonical.title, record.title, canonical.creditedArtist)
        ) return DiscogsEditionVerdict.CONFLICT

        val artist = canonical.creditedArtist?.takeIf(String::isNotBlank)
            ?: return DiscogsEditionVerdict.CANDIDATE
        if (record.artistNames.isEmpty()) return DiscogsEditionVerdict.CANDIDATE
        if (record.artistNames.none { normalized(it) == normalized(artist) }) {
            return DiscogsEditionVerdict.CONFLICT
        }

        // Unofficial *edition* observations are not claims about the entire RG.
        // Never validate an officially classified MB edition against one.
        if (record.unofficial) {
            return if (canonical.status.equals("Official", ignoreCase = true)) {
                DiscogsEditionVerdict.CONFLICT
            } else {
                DiscogsEditionVerdict.CANDIDATE
            }
        }

        val mbBarcodes = canonical.barcodes.mapNotNull(::barcode).toSet()
        val externalBarcodes = record.barcodes.mapNotNull(::barcode).toSet()
        val bothHaveBarcodes = mbBarcodes.isNotEmpty() && externalBarcodes.isNotEmpty()
        val barcodeMatches = bothHaveBarcodes && mbBarcodes.any { it in externalBarcodes }
        if (bothHaveBarcodes && !barcodeMatches) return DiscogsEditionVerdict.CONFLICT

        val mbTracks = canonical.trackTitles.map(::normalized)
        val externalTracks = record.tracklist.map { normalized(it.second) }
        val bothHaveTracks = mbTracks.isNotEmpty() && externalTracks.isNotEmpty()
        val trackMatches = bothHaveTracks && mbTracks == externalTracks
        // A conflicting tracklist can signal a different pressing, remix or bonus edition.
        if (bothHaveTracks && !trackMatches) return DiscogsEditionVerdict.CANDIDATE

        return if (barcodeMatches || trackMatches) {
            DiscogsEditionVerdict.VERIFIED
        } else {
            DiscogsEditionVerdict.CANDIDATE
        }
    }

    /**
     * Discogs' release.title commonly contains a leading "Artist - " while
     * MusicBrainz release.title does not. Strip only a *known credited artist*
     * prefix; creditedArtist is checked against Discogs artistNames above.
     * This is comparison-only; it never rewrites either source title.
     */
    private fun sameEditionTitle(album: String, discogsTitle: String, artistCredit: String?): Boolean {
        val title = normalized(album)
        val observed = normalized(discogsTitle)
        if (observed == title) return true
        val artist = artistCredit?.takeIf(String::isNotBlank) ?: return false
        return observed == normalized(artist) + " - " + title
    }

    private fun normalized(s: String): String =
        s.trim().replace(spaces, " ").lowercase(Locale.ROOT)

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
