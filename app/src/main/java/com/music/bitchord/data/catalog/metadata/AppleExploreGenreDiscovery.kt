package com.music.bitchord.data.catalog.metadata

import java.text.Normalizer
import java.util.Locale
import com.music.bitchord.data.providers.ProviderCapability

/**
 * MusicBrainz tag browse yields search hints, but cannot assign a category to
 * an Apple product by name. The Apple album's primaryGenreName is the only
 * currently available storefront genre signal used by Explore.
 *
 * Conservative exact names avoid treating mainstream Pop as K-pop or broad
 * Alternative/Rock as evidence of a Metal/Punk edition.
 */
internal object AppleExploreGenrePolicy {
    private val known = mapOf(
        "pop" to setOf("pop"),
        "rock" to setOf("rock"),
        "hip-hop" to setOf("hip-hop/rap", "hip hop/rap", "hip-hop", "hip hop"),
        "electronic" to setOf("electronic", "electronica", "eletronica"),
        "k-pop" to setOf("k-pop", "kpop"),
        "rnb" to setOf("r&b/soul", "r&b", "rnb"),
        "jazz" to setOf("jazz"),
        "metal" to setOf("metal"),
        "classical" to setOf("classical", "classica"),
        "folk" to setOf("folk"),
        "ambient" to setOf("ambient"),
        "punk" to setOf("punk"),
    )
    private fun normalized(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")

    fun supports(tag: String): Boolean = tag in known

    /** Search term is only a discovery hint; the Apple genre claim is verified below. */
    fun nativeSearchTerm(tag: String): String? = if (!supports(tag)) null else when (tag) {
        "rnb" -> "R&B"
        "hip-hop" -> "Hip-Hop"
        "k-pop" -> "K-Pop"
        else -> tag
    }

    fun matches(tag: String, applePrimaryGenre: String?): Boolean {
        val allowed = known[tag] ?: return false
        if (applePrimaryGenre.isNullOrBlank()) return false
        return normalized(applePrimaryGenre) in allowed
    }
}

internal fun interface AppleNativeGenreSearchSource {
    suspend fun search(term: String, storefront: String): AppleEditorialLoad
}

internal class AppleExploreGenreDiscovery(
    private val catalog: AppleHomeEditorialDiscovery = AppleHomeEditorialDiscovery(),
    private val nativeSource: AppleNativeGenreSearchSource =
        AppleNativeGenreSearchSource { term, storefront ->
            AppleEditorialCatalogRepository().searchAlbums(
                query = term, storefront = storefront, limit = 100,
                capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                attribute = ItunesCatalogSearchAttribute.GENRE_INDEX,
            )
        },
) {
    /** Independent Apple search. No MusicBrainz call, seed, MBID or title crosswalk. */
    suspend fun discoverNative(
        tag: String,
        storefront: String = "br",
        onProgress: suspend (List<ItunesEditorialRelease>) -> Unit = {},
    ): AppleEditorialLoad {
        val term = AppleExploreGenrePolicy.nativeSearchTerm(tag)
            ?: return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        if (!Regex("^[a-z]{2}$").matches(storefront)) {
            return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        return when (val found = nativeSource.search(term, storefront)) {
            is AppleEditorialLoad.Failure -> found
            is AppleEditorialLoad.Success -> {
                val matching = found.releases.asSequence().filter { row ->
                    row.storefront == storefront &&
                        row.appleCollectionId > 0L &&
                        row.artistId?.let { it > 0L } == true &&
                        row.appleScopedId ==
                            "itunes:$storefront:album:${row.appleCollectionId}" &&
                        row.title.isNotBlank() && row.artistName.isNotBlank() &&
                        AppleExploreGenrePolicy.matches(tag, row.genre)
                }.map { row ->
                    row.copy(
                        musicBrainzReleaseGroupId = null, musicBrainzReleaseId = null,
                        musicBrainzArtistId = null, musicBrainzRecordingIds = emptyMap(),
                    )
                }.groupBy { it.appleScopedId }.values.mapNotNull { rows ->
                    rows.firstOrNull()?.takeIf { first -> rows.all { it == first } }
                }.take(24)
                onProgress(matching)
                AppleEditorialLoad.Success(
                    matching,
                    when {
                        found.state == AppleEditorialReadState.POSSIBLY_TRUNCATED ->
                            AppleEditorialReadState.POSSIBLY_TRUNCATED
                        matching.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                        else -> found.state
                    },
                    found.probeReport,
                )
            }
        }
    }

    suspend fun discover(
        tag: String,
        seeds: List<AppleHomeEditorialSeed>,
        storefront: String = "br",
        onProgress: suspend (List<ItunesEditorialRelease>) -> Unit = {},
    ): AppleEditorialLoad {
        if (!AppleExploreGenrePolicy.supports(tag)) {
            return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        return when (val source = catalog.discover(
            seeds, storefront, maxProbes = 8,
            onProgress = { rows ->
                onProgress(rows.filter {
                    AppleExploreGenrePolicy.matches(tag, it.genre)
                })
            },
        )) {
            is AppleEditorialLoad.Failure -> source
            is AppleEditorialLoad.Success -> {
                val matched = source.releases.filter {
                    AppleExploreGenrePolicy.matches(tag, it.genre)
                }
                AppleEditorialLoad.Success(
                    matched,
                    when {
                        source.state == AppleEditorialReadState.POSSIBLY_TRUNCATED ->
                            AppleEditorialReadState.POSSIBLY_TRUNCATED
                        matched.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                        else -> AppleEditorialReadState.COMPLETE
                    },
                    // Genre filtering can reject a perfectly valid Apple
                    // album. Report actual API yield, not only visible tiles.
                    probeReport = source.probeReport,
                )
            }
        }
    }
}
