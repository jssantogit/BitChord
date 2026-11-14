package com.music.bitchord.data.catalog.metadata

import java.text.Normalizer
import java.util.Locale

/**
 * Offline, read-only proof. A matching UPC alone is NOT an edition crosswalk.
 * For now, independently verified exact MB Release/ReleaseGroup identity requires:
 * Official status + valid group + matching UPC + credited artist + exact Apple
 * display title (removing only its explicit EP/Single suffix) + complete ordered
 * tracklist and recording identifiers + non-conflicting release year.
 *
 * Storefront is the Apple product namespace, not the MB release country.
 * False negatives are intentionally preferred to assigning a wrong MBID.
 */
internal sealed interface AppleMusicBrainzEditionMatch {
    data class Verified(
        val appleScopedId: String,
        val musicBrainzReleaseId: String,
        val musicBrainzReleaseGroupId: String,
        val recordingLinks: List<ItunesVerifiedRecordingLink>,
    ) : AppleMusicBrainzEditionMatch {
        val releaseLink: ItunesVerifiedReleaseLink
            get() = ItunesVerifiedReleaseLink(appleScopedId, musicBrainzReleaseId)
        val groupLink: ItunesVerifiedReleaseGroupLink
            get() = ItunesVerifiedReleaseGroupLink(appleScopedId, musicBrainzReleaseGroupId)
    }

    data class Ambiguous(val musicBrainzReleaseIds: List<String>) : AppleMusicBrainzEditionMatch
    data class Candidate(val musicBrainzReleaseIds: List<String>) : AppleMusicBrainzEditionMatch
    data object NoMatch : AppleMusicBrainzEditionMatch
}

internal object AppleMusicBrainzEditionMatcher {
    private val mbid = Regex("^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
    private val upcPattern = Regex("^[0-9]{8,14}$")
    private val suffix = Regex("""\s[-–—]\s(?:EP|Single)$""", RegexOption.IGNORE_CASE)
    private val space = Regex("""\s+""")

    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .trim().replace(space, " ").lowercase(Locale.ROOT)

    private fun validMbid(raw: String?): String? =
        raw?.trim()?.lowercase(Locale.ROOT)?.takeIf(mbid::matches)

    private enum class Grade { NO_MATCH, CANDIDATE, VERIFIED }

    private fun check(
        apple: ItunesEditorialRelease,
        mb: MusicBrainzCatalogReleaseDetail,
        exactLinkedAlbum: Boolean = false,
    ): Grade {
        val group = validMbid(mb.groupMbid) ?: return Grade.NO_MATCH
        if (validMbid(mb.musicBrainzId) == null ||
            !mb.status.equals("Official", ignoreCase = true) ||
            apple.musicBrainzReleaseGroupId?.let { validMbid(it) != group } == true ||
            !Regex("^[a-z]{2}$").matches(apple.storefront) ||
            apple.appleScopedId != "itunes:${apple.storefront}:album:${apple.appleCollectionId}" ||
            apple.appleCollectionId <= 0L
        ) return Grade.NO_MATCH

        val appleTitle = normalized(apple.title.replace(suffix, ""))
        if (appleTitle.isBlank() || appleTitle != normalized(mb.title) ||
            normalized(apple.artistName) != normalized(mb.artistDisplay.orEmpty()) ||
            mb.artistCreditNames.isEmpty()
        ) return Grade.NO_MATCH

        // Only the dedicated exact Release URL crosswalk may bypass missing
        // UPC. General catalog matching NEVER treats title/tracklist alone
        // as release-level identity. Multiple explicit links are ambiguous.
        if (exactLinkedAlbum &&
            mb.linkedAppleAlbumIds.distinct() != listOf(apple.appleCollectionId)
        ) return Grade.NO_MATCH
        val aUpc = apple.upc?.trim()?.takeIf(String::isNotEmpty)
        val mbUpc = mb.barcode?.trim()?.takeIf(String::isNotEmpty)
        if ((aUpc != null && !upcPattern.matches(aUpc)) ||
            (mbUpc != null && !upcPattern.matches(mbUpc)) ||
            (aUpc != null && mbUpc != null && aUpc != mbUpc)
        ) return Grade.NO_MATCH
        if (!exactLinkedAlbum && (aUpc == null || mbUpc == null))
            return Grade.CANDIDATE

        val appleYear = apple.releaseDate?.take(4)?.toIntOrNull()
        val mbYear = mb.releaseDate?.take(4)?.toIntOrNull()
        if (appleYear != null && mbYear != null && appleYear != mbYear)
            return Grade.CANDIDATE

        if (!mb.hasCompleteTracklist ||
            !mb.completeness.countsExplicitAndExact ||
            !mb.completeness.offsetsValid ||
            apple.trackCount == null || apple.trackCount <= 0 ||
            apple.tracks.size != apple.trackCount || mb.tracks.size != apple.trackCount
        ) return Grade.CANDIDATE

        val sortedApple = apple.tracks.sortedWith(
            compareBy<ItunesCatalogItem> { it.discNumber ?: Int.MAX_VALUE }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
        )
        val sortedMb = mb.tracks.sortedWith(
            compareBy<MusicBrainzCatalogTrack> { it.mediumNumber }
                .thenBy { it.trackNumber }
        )

        if (sortedApple.any { it.type != ItunesCatalogItemType.TRACK ||
                it.storefront != apple.storefront ||
                it.collectionId != apple.appleCollectionId ||
                it.id <= 0L || it.trackNumber == null || it.trackNumber <= 0 ||
                it.discNumber == null || it.discNumber <= 0
            } ||
            sortedApple.map { it.scopedId }.distinct().size != sortedApple.size ||
            sortedApple.map { it.discNumber to it.trackNumber }.distinct().size != sortedApple.size ||
            sortedMb.map { it.trackMbid }.distinct().size != sortedMb.size ||
            sortedMb.map { it.mediumNumber to it.trackNumber }.distinct().size != sortedMb.size ||
            sortedMb.any { it.mediumNumber <= 0 || it.trackNumber <= 0 ||
                validMbid(it.trackMbid) == null || validMbid(it.recordingMbid) == null }
        ) return Grade.CANDIDATE

        if (sortedApple.zip(sortedMb).any { (track, other) ->
            track.discNumber != other.mediumNumber ||
                track.trackNumber != other.trackNumber ||
                normalized(track.title) != normalized(other.title)
        }) return Grade.NO_MATCH

        return Grade.VERIFIED
    }

    fun match(
        apple: ItunesEditorialRelease,
        candidates: List<MusicBrainzCatalogReleaseDetail>,
    ): AppleMusicBrainzEditionMatch {
        // Conflicting snapshots under one MBID must not select a winner.
        val groups = candidates.groupBy { validMbid(it.musicBrainzId) ?: it.musicBrainzId }
        val contested = groups.filterValues { snapshots ->
            snapshots.any { it != snapshots.first() }
        }
        // A malformed unrelated MB edition must not poison the entire Apple
        // result. A disagreement touching this Apple edition, however, must
        // veto certification, even when one snapshot matched perfectly.
        val relevantContested = contested.filterValues { snapshots ->
            snapshots.any { check(apple, it) != Grade.NO_MATCH }
        }.keys
        val unique = groups.filterKeys { it !in contested.keys }
            .values.map { it.first() }
        val assessed = unique.map { it to check(apple, it) }
        val verified = assessed.filter { it.second == Grade.VERIFIED }
            .map { it.first }
        val pending = assessed.filter { it.second == Grade.CANDIDATE }
            .map { it.first.musicBrainzId }
        if (relevantContested.isNotEmpty()) return AppleMusicBrainzEditionMatch.Ambiguous(
            (verified.map { it.musicBrainzId } + pending + relevantContested).distinct().sorted()
        )
        if (verified.size > 1 || (verified.isNotEmpty() && pending.isNotEmpty())) {
            return AppleMusicBrainzEditionMatch.Ambiguous(
                (verified.map { it.musicBrainzId } + pending).distinct().sorted(),
            )
        }
        if (verified.isEmpty()) {
            return if (pending.isEmpty()) AppleMusicBrainzEditionMatch.NoMatch
            else AppleMusicBrainzEditionMatch.Candidate(pending.distinct().sorted())
        }
        val mb = verified.single()
        return verifiedMatch(apple, mb)
    }

    /**
     * An exact MB Release→Apple album URL relationship is stronger than a
     * loose title comparison, but still needs Official status, credited
     * artist, release date, complete positional tracklist and real Recording
     * IDs. No release-group or artist-level URLs qualify.
     */
    fun matchExplicitLinkedAlbum(
        apple: ItunesEditorialRelease,
        mb: MusicBrainzCatalogReleaseDetail,
    ): AppleMusicBrainzEditionMatch =
        if (check(apple, mb, exactLinkedAlbum = true) == Grade.VERIFIED)
            verifiedMatch(apple, mb)
        else AppleMusicBrainzEditionMatch.NoMatch

    private fun verifiedMatch(
        apple: ItunesEditorialRelease,
        mb: MusicBrainzCatalogReleaseDetail,
    ): AppleMusicBrainzEditionMatch.Verified =
        AppleMusicBrainzEditionMatch.Verified(
            appleScopedId = apple.appleScopedId,
            musicBrainzReleaseId = validMbid(mb.musicBrainzId)!!,
            musicBrainzReleaseGroupId = validMbid(mb.groupMbid)!!,
            recordingLinks = apple.tracks.sortedWith(
                compareBy<ItunesCatalogItem> { it.discNumber }.thenBy { it.trackNumber }
            ).zip(mb.tracks.sortedWith(
                compareBy<MusicBrainzCatalogTrack> { it.mediumNumber }.thenBy { it.trackNumber }
            )).map { (a, m) ->
                ItunesVerifiedRecordingLink(a.scopedId, validMbid(m.recordingMbid)!!)
            },
        )
}