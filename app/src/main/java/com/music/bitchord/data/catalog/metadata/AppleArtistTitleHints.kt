package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsShadowRelease
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsSourceOnlyDiscovery
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Discovery suggestions ONLY. A title does not imply a MB↔Apple↔Discogs
 * identity crosswalk, a commercial product, album artwork, or playback.
 */
internal fun interface AppleArtistTitleHintSource {
    suspend fun suggest(artistName: String, verifiedMbArtistId: String?): List<String>
}

internal object AppleArtistTitleHintPolicy {
    private val whitespace = Regex("""\s+""")
    private val suffix = Regex("""\s[-–—]\s(?:EP|Single)$""", RegexOption.IGNORE_CASE)
    private const val MAX_SUGGESTIONS = 24

    private fun normalized(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
            .replace(suffix, "").replace(whitespace, " ")
            .lowercase(Locale.ROOT)

    fun select(
        artistName: String,
        mbGroups: List<MusicBrainzReleaseGroupHit>,
        discogsEditions: List<DiscogsShadowRelease>,
        existingAppleTitles: List<String>,
    ): List<String> {
        val artistKey = normalized(artistName)
        if (artistKey.length !in 1..120) return emptyList()
        val excluded = existingAppleTitles.mapTo(mutableSetOf(), ::normalized)
        val musicBrainzHints = mutableListOf<String>()
        val discogsHints = mutableListOf<String>()
        val seen = excluded.toMutableSet()

        fun add(raw: String, credited: String, into: MutableList<String>) {
            val title = raw.trim()
            val key = normalized(title)
            if (normalized(credited) != artistKey ||
                title.length !in 2..120 || title.any(Char::isISOControl) ||
                key in seen || !canonicalStreamingTitleIsSafe(title)
            ) return
            seen += key
            into += title
        }
        mbGroups.asSequence()
            .filter { isCuratedMusicBrainzReleaseGroup(it) }
            .take(200)
            .forEach { add(it.title, it.artistDisplay.orEmpty(), musicBrainzHints) }
        discogsEditions.asSequence()
            .filter { it.discogsReleaseId > 0L &&
                it.attributionUrl == "https://www.discogs.com/release/${it.discogsReleaseId}" &&
                !it.canPlay && it.musicBrainzId == null }
            .take(32)
            .forEach { add(it.title, it.artist, discogsHints) }
        // A large MB catalogue must not permanently crowd out exact-artist
        // Discogs cache hints. Both remain candidate names; only Apple can
        // confirm a visible album. Fill unused space from either source.
        return (musicBrainzHints.take(18) + discogsHints.take(6) +
            musicBrainzHints.drop(18) + discogsHints.drop(6))
            .take(MAX_SUGGESTIONS)
    }
}

/**
 * Read-only public discovery. MB name searches create *candidates* only.
 * Discogs evidence is included ONLY from an artist-scoped, still-live cache
 * attached to an independently verified MB artist ID. A name match cannot
 * authorize access to another artist's Discogs artist-index.
 */
internal class MusicBrainzDiscogsAppleTitleHintSource(
    private val musicBrainz: MusicBrainzCatalogSearch = MusicBrainzCatalogSearch(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AppleArtistTitleHintSource {
    override suspend fun suggest(
        artistName: String,
        verifiedMbArtistId: String?,
    ): List<String> {
        val result = try {
            if (verifiedMbArtistId != null) {
                musicBrainz.searchReleaseGroupsForArtistMbid(verifiedMbArtistId, limit = 100)
            } else {
                musicBrainz.searchReleaseGroups(artistName, limit = 100)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyList()
        }
        val groups = when (result) {
            is MusicBrainzCatalogSearchResult.Success -> result.page.items
            is MusicBrainzCatalogSearchResult.Failure -> emptyList()
        }
        val discogs = verifiedMbArtistId?.let { id ->
            DiscogsSourceOnlyDiscovery.store.activeForArtist(id, nowMillis())
        }.orEmpty()
        return AppleArtistTitleHintPolicy.select(
            artistName, groups, discogs, emptyList(),
        )
    }
}
