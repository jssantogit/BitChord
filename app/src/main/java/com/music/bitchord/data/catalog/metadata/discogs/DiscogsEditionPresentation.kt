package com.music.bitchord.data.catalog.metadata.discogs

import java.util.Locale

/**
 * Provider-neutral UI callers may consume this small, immutable supplementary
 * read model without coupling to transport, Room, Discogs JSON or MediaAsset.
 *
 * The attribution is inseparable from the supplied fields. A caller must link
 * [attributionUrl] adjacent to the rendered supplementary metadata; the About
 * screen also needs the non-affiliation notice before the provider is enabled.
 * No cover, stream, catalog type, "official" certification or personal data.
 */
/**
 * Discogs format descriptions are independent editorial evidence, not a new
 * canonical ReleaseGroup type. Never infer Album from LP or track counts.
 */
internal enum class DiscogsEditorialKind(val displayName: String) {
    ALBUM("Álbum"),
    MINI_ALBUM("Miniálbum"),
    EP("EP"),
    SINGLE("Single"),
    MAXI_SINGLE("Maxi-single"),
    MIXTAPE("Mixtape"),
}

internal fun discogsEditorialKind(formats: List<String>): DiscogsEditorialKind? {
    val types = formats.mapNotNull { value ->
        when (value.trim().lowercase(Locale.ROOT)) {
            "album" -> DiscogsEditorialKind.ALBUM
            "mini-album" -> DiscogsEditorialKind.MINI_ALBUM
            "ep" -> DiscogsEditorialKind.EP
            "single" -> DiscogsEditorialKind.SINGLE
            "maxi-single" -> DiscogsEditorialKind.MAXI_SINGLE
            "mixtape" -> DiscogsEditorialKind.MIXTAPE
            else -> null
        }
    }.toSet()
    // Mixed Album/EP/Single declarations are ambiguous and remain unclassified.
    return types.singleOrNull()
}

internal data class DiscogsEditionPresentation(
    val country: String?,
    val editionYear: Int?,
    val labelLines: List<String>,
    val formats: List<String>,
    val attributionText: String,
    val attributionUrl: String,
    val editorialKind: DiscogsEditorialKind? = null,
) {
    companion object {
        fun from(
            evidence: DiscogsEditionEnrichment,
            nowMillis: Long,
        ): DiscogsEditionPresentation? {
            val verified = evidence as? DiscogsEditionEnrichment.Verified ?: return null
            val observation = verified.observation
            if (!observation.isDisplayableAt(nowMillis) ||
                observation.record.unofficial ||
                observation.record.link.kind != DiscogsKind.RELEASE
            ) return null
            val record = observation.record
            return DiscogsEditionPresentation(
                country = record.country,
                editionYear = record.year,
                labelLines = record.labels.map { (name, catalog) ->
                    if (catalog.isBlank()) name else "$name ($catalog)"
                }.distinct().take(16),
                formats = record.formats.distinct().take(32),
                editorialKind = discogsEditorialKind(record.formats),
                attributionText = verified.attributionText,
                attributionUrl = verified.attributionUrl,
            )
        }
    }
}
