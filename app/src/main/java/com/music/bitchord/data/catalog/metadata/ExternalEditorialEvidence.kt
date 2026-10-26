package com.music.bitchord.data.catalog.metadata

import java.util.Locale

/**
 * Read-only observations from future provider adapters.
 * These are *not* canonical IDs, playable MediaAssets, or assertions that the
 * provider permits artwork redistribution. No external API is called here.
 */
internal enum class CatalogExternalProvider { LAST_FM, LISTENBRAINZ, APPLE_MUSIC }
internal enum class CatalogTargetKind { ARTIST, RELEASE_GROUP, RELEASE, RECORDING }
internal enum class CatalogEvidenceBasis {
    /** Confirmed upstream MBID or an independently verified crosswalk. */
    DIRECT_MBID,
    /** A candidate match requiring edition + artist evidence. */
    UPC_AND_ARTIST,
    /** Recording-level candidate, never edition proof on its own. */
    ISRC_AND_ARTIST,
    /** Text resemblance is never a canonical identity claim. */
    TITLE_ONLY,
}
internal enum class CatalogEvidenceDecision { VERIFIED, CANDIDATE, REJECTED }
internal enum class CatalogArtworkRight { ALLOWED, UNKNOWN, RESTRICTED }

internal data class ExternalEditorialEvidence(
    val provider: CatalogExternalProvider,
    val externalId: String,
    val targetKind: CatalogTargetKind,
    val canonicalMbid: String,
    val basis: CatalogEvidenceBasis,
    /** Apple catalog IDs must be scoped to the issuing storefront. */
    val storefront: String? = null,
    /** Explicitly verified display rights, defaulting safely to unknown. */
    val artworkRight: CatalogArtworkRight = CatalogArtworkRight.UNKNOWN,
) {
    private fun normalizedId(): String? {
        val id = externalId.trim()
        if (id.isEmpty() || id.length > 180 || id.contains('/') ||
            id.contains('\\') || id.any { it.isISOControl() }
        ) return null
        return id
    }

    fun assess(): CatalogEvidenceDecision {
        if (!MBID.matches(canonicalMbid.lowercase(Locale.ROOT)) ||
            normalizedId() == null
        ) return CatalogEvidenceDecision.REJECTED
        if (provider == CatalogExternalProvider.APPLE_MUSIC &&
            (storefront == null || !Regex("[a-z]{2}").matches(
                storefront.lowercase(Locale.ROOT)))
        ) return CatalogEvidenceDecision.REJECTED
        return when (basis) {
            CatalogEvidenceBasis.TITLE_ONLY -> CatalogEvidenceDecision.REJECTED
            CatalogEvidenceBasis.UPC_AND_ARTIST ->
                if (targetKind == CatalogTargetKind.RELEASE) CatalogEvidenceDecision.CANDIDATE
                else CatalogEvidenceDecision.REJECTED
            CatalogEvidenceBasis.ISRC_AND_ARTIST ->
                if (targetKind == CatalogTargetKind.RECORDING) CatalogEvidenceDecision.CANDIDATE
                else CatalogEvidenceDecision.REJECTED
            CatalogEvidenceBasis.DIRECT_MBID -> CatalogEvidenceDecision.VERIFIED
        }
    }

    fun scopedExternalKey(): String? {
        val id = normalizedId() ?: return null
        val providerId = provider.name.lowercase(Locale.ROOT)
        return if (provider == CatalogExternalProvider.APPLE_MUSIC) {
            val region = storefront?.lowercase(Locale.ROOT)
                ?.takeIf { Regex("[a-z]{2}").matches(it) } ?: return null
            "$providerId:$region:$id"
        } else "$providerId:$id"
    }

    fun canSupplyArtwork(): Boolean =
        assess() == CatalogEvidenceDecision.VERIFIED &&
            artworkRight == CatalogArtworkRight.ALLOWED &&
            targetKind in setOf(CatalogTargetKind.RELEASE, CatalogTargetKind.ARTIST)

    /** An external metadata match never grants audio ownership or permission. */
    fun canClaimPlayableAudio(): Boolean = false
}
