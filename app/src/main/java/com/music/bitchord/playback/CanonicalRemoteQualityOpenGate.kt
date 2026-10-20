package com.music.bitchord.playback

/**
 * B13 preparatory policy, not the Catalog HTTP/Media3 integration.
 *
 * A request decision is not a successful transcode, codec observation, or proof
 * that audio became audible. Catalog remains the sole owner of account
 * generations, advertised/verified capability evidence and authenticated opens.
 */
internal enum class CanonicalRemoteQualityOpenMode {
    ORIGINAL,
    REQUEST_CAPPED_MP3,
    BLOCK_UNAPPLIED,
}

internal enum class CanonicalRemoteQualityOpenReason {
    ORIGINAL_OR_UNVERIFIED,
    VERIFIED_REQUEST_NOT_APPLIED,
    GENERATION_UNAVAILABLE,
    CEILING_NOT_SUPPORTED,
    INVALID_PREVIEW,
}

/**
 * Immutable candidate for the next explicit stream open only.
 * It has no recording IDs, provider locators, secrets, network side effects
 * or mutable reference to the active Media3 player.
 */
internal data class CanonicalRemoteQualityOpenPlan(
    val mode: CanonicalRemoteQualityOpenMode,
    val reason: CanonicalRemoteQualityOpenReason,
    val accountGeneration: Long? = null,
    val maxBitrateBps: Int? = null,
    val applied: Boolean = false,
) {
    init {
        require(!applied) { "Preparation is never proof of applied quality" }
        if (mode == CanonicalRemoteQualityOpenMode.REQUEST_CAPPED_MP3) {
            require(accountGeneration != null && accountGeneration >= 0L)
            require(maxBitrateBps != null && maxBitrateBps in 32_000..512_000)
        } else {
            require(maxBitrateBps == null)
            require(accountGeneration == null)
        }
    }
}

/**
 * Pure Playback-owned guard. Only a separately proven per-media capability
 * can reach REQUEST_CAPPED_MP3. The consumer must revalidate at DataSource.open
 * using a Catalog-owned generation/lease and the real per-asset decision.
 *
 * No set of accepted ceilings is manufactured here; until Catalog supplies
 * verified support the caller passes emptySet() and MP3 stays blocked.
 */
internal object CanonicalRemoteQualityOpenGate {
    fun plan(
        preview: CanonicalRemoteQualityPreview,
        accountGeneration: Long?,
        supportedMp3CeilingsBps: Set<Int>,
    ): CanonicalRemoteQualityOpenPlan {
        fun block(reason: CanonicalRemoteQualityOpenReason) =
            CanonicalRemoteQualityOpenPlan(
                mode = CanonicalRemoteQualityOpenMode.BLOCK_UNAPPLIED,
                reason = reason,
            )

        // Reject even an internally inconsistent input, rather than
        // accidentally treating previously observed/requested as applied.
        if (preview.applied ||
            preview.negotiatedFormat != null ||
            preview.observedFormat != null
        ) return block(CanonicalRemoteQualityOpenReason.INVALID_PREVIEW)

        if (preview.decision != CanonicalRemoteQualityDecision.CAPABILITY_VERIFIED_INTENT) {
            return CanonicalRemoteQualityOpenPlan(
                mode = CanonicalRemoteQualityOpenMode.ORIGINAL,
                reason = CanonicalRemoteQualityOpenReason.ORIGINAL_OR_UNVERIFIED,
            )
        }

        // A forged/invalid preview is never enough to authorize a variant.
        if (preview.requestFormat != "mp3" || preview.chosenPreference == null ||
            preview.chosenPreference == com.music.bitchord.data.settings.AudioQuality.LOSSLESS
        ) return block(CanonicalRemoteQualityOpenReason.INVALID_PREVIEW)

        if (accountGeneration == null || accountGeneration < 0L) {
            return block(CanonicalRemoteQualityOpenReason.GENERATION_UNAVAILABLE)
        }

        val kbps = preview.maxBitrateKbps
            ?: return block(CanonicalRemoteQualityOpenReason.CEILING_NOT_SUPPORTED)
        if (kbps <= 0 || kbps > 512 ||
            kbps > preview.chosenPreference.maxKbps
        ) return block(CanonicalRemoteQualityOpenReason.CEILING_NOT_SUPPORTED)

        val bitrate = kbps * 1_000
        if (bitrate !in 32_000..512_000 || bitrate !in supportedMp3CeilingsBps) {
            return block(CanonicalRemoteQualityOpenReason.CEILING_NOT_SUPPORTED)
        }

        return CanonicalRemoteQualityOpenPlan(
            mode = CanonicalRemoteQualityOpenMode.REQUEST_CAPPED_MP3,
            reason = CanonicalRemoteQualityOpenReason.VERIFIED_REQUEST_NOT_APPLIED,
            accountGeneration = accountGeneration,
            maxBitrateBps = bitrate,
            applied = false,
        )
    }
}
