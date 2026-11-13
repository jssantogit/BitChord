package com.music.bitchord.data.catalog.metadata

import java.text.Normalizer
import java.util.Locale

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

    fun matches(tag: String, applePrimaryGenre: String?): Boolean {
        val allowed = known[tag] ?: return false
        if (applePrimaryGenre.isNullOrBlank()) return false
        return normalized(applePrimaryGenre) in allowed
    }
}

internal class AppleExploreGenreDiscovery(
    private val catalog: AppleHomeEditorialDiscovery = AppleHomeEditorialDiscovery(),
) {
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
