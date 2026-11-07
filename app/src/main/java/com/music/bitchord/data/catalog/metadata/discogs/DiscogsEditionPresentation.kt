package com.music.bitchord.data.catalog.metadata.discogs

/**
 * Provider-neutral UI callers may consume this small, immutable supplementary
 * read model without coupling to transport, Room, Discogs JSON or MediaAsset.
 *
 * The attribution is inseparable from the supplied fields. A caller must link
 * [attributionUrl] adjacent to the rendered supplementary metadata; the About
 * screen also needs the non-affiliation notice before the provider is enabled.
 * No cover, stream, catalog type, "official" certification or personal data.
 */
internal data class DiscogsEditionPresentation(
    val country: String?,
    val editionYear: Int?,
    val labelLines: List<String>,
    val formats: List<String>,
    val attributionText: String,
    val attributionUrl: String,
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
                attributionText = verified.attributionText,
                attributionUrl = verified.attributionUrl,
            )
        }
    }
}
