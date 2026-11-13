package com.music.bitchord.data.catalog.metadata

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * MusicBrainz release-group metadata can provide non-authoritative *search terms*.
 * The Apple results, not the seeds, own all product titles, dates, IDs and art.
 * No MBIDs or playback URLs are accepted from a seed or copied into the feed.
 */
internal data class AppleHomeEditorialSeed(val title: String, val artist: String)

internal fun interface AppleHomeAlbumSearchSource {
    suspend fun search(title: String, storefront: String): AppleEditorialLoad
}

internal class AppleHomeEditorialDiscovery(
    private val source: AppleHomeAlbumSearchSource =
        AppleHomeAlbumSearchSource { title, storefront ->
            AppleEditorialCatalogRepository().searchAlbums(
                query = title, storefront = storefront, limit = 100,
            )
        },
) {
    private val whitespace = Regex("""\s+""")
    private val editorialSuffix = Regex(
        """\s[-–—]\s(?:EP|Single)$""", RegexOption.IGNORE_CASE,
    )
    private fun normalized(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            .replace(whitespace, " ").lowercase(Locale.ROOT)

    private fun titleKey(value: String): String =
        normalized(value.replace(editorialSuffix, ""))

    suspend fun discover(
        seeds: List<AppleHomeEditorialSeed>,
        storefront: String,
        maxProbes: Int = 8,
        /** On each changed Apple-confirmed snapshot, before later probes finish. */
        onProgress: suspend (List<ItunesEditorialRelease>) -> Unit = {},
    ): AppleEditorialLoad {
        if (!Regex("^[a-z]{2}$").matches(storefront) || maxProbes !in 1..12) {
            return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        val safeSeeds = seeds.asSequence()
            .map { it.copy(title = it.title.trim(), artist = it.artist.trim()) }
            .filter { seed ->
                seed.title.length in 2..120 && seed.artist.length in 1..120 &&
                    seed.title.none(Char::isISOControl) &&
                    seed.artist.none(Char::isISOControl) &&
                    canonicalStreamingTitleIsSafe(seed.title)
            }
            .distinctBy { titleKey(it.title) to normalized(it.artist) }
            .toList()
        if (safeSeeds.isEmpty()) return AppleEditorialLoad.Success(
            emptyList(), AppleEditorialReadState.NOT_IN_STOREFRONT,
        )
        val selected = safeSeeds.take(maxProbes)
        var uncertain = selected.size < safeSeeds.size
        var succeeded = false
        val verified = linkedMapOf<String, ItunesEditorialRelease>()
        val disputed = mutableSetOf<String>()
        var lastPublished: List<ItunesEditorialRelease>? = null
        fun snapshot(): List<ItunesEditorialRelease> =
            verified.values.sortedWith(
                compareByDescending<ItunesEditorialRelease> { it.releaseDate.orEmpty() }
                    .thenBy { it.appleCollectionId },
            )
        for (seed in selected) {
            val response = try {
                source.search(seed.title, storefront)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppleEditorialLoad.Failure(ItunesCatalogFailure.UNAVAILABLE)
            }
            when (response) {
                is AppleEditorialLoad.Failure -> uncertain = true
                is AppleEditorialLoad.Success -> {
                    succeeded = true
                    if (response.state == AppleEditorialReadState.POSSIBLY_TRUNCATED ||
                        response.state == AppleEditorialReadState.PARTIAL_TRACKLIST
                    ) uncertain = true
                    response.releases.forEach { album ->
                        // Matching an MB search *phrase* authorizes no MBID link:
                        // show only what the independent Apple endpoint says.
                        if (album.storefront != storefront || album.appleCollectionId <= 0L ||
                            album.appleScopedId !=
                                "itunes:$storefront:album:${album.appleCollectionId}" ||
                            album.artistId == null || album.artistId <= 0L ||
                            normalized(album.artistName) != normalized(seed.artist) ||
                            titleKey(album.title) != titleKey(seed.title)
                        ) return@forEach
                        val clean = album.copy(
                            musicBrainzReleaseGroupId = null,
                            musicBrainzReleaseId = null,
                            musicBrainzArtistId = null,
                            musicBrainzRecordingIds = emptyMap(),
                        )
                        if (clean.appleScopedId in disputed) return@forEach
                        val previous = verified[clean.appleScopedId]
                        if (previous == null) verified[clean.appleScopedId] = clean
                        else if (previous != clean) {
                            verified.remove(clean.appleScopedId)
                            disputed += clean.appleScopedId
                            uncertain = true
                        }
                    }
                    val current = snapshot()
                    if (current != lastPublished &&
                        (current.isNotEmpty() || lastPublished != null)
                    ) {
                        lastPublished = current
                        onProgress(current)
                    }
                }
            }
        }
        if (!succeeded) return AppleEditorialLoad.Failure(ItunesCatalogFailure.UNAVAILABLE)
        return AppleEditorialLoad.Success(
            releases = snapshot(),
            state = when {
                uncertain -> AppleEditorialReadState.POSSIBLY_TRUNCATED
                verified.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                else -> AppleEditorialReadState.COMPLETE
            },
        )
    }
}
