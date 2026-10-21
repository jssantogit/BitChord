package com.music.bitchord.data.catalog.provider.opensubsonic

/**
 * Minimum HTTP decoder profile the provider is willing to request. Codec and
 * protocol support still require an actual client/Media3 gate at open time.
 * In particular, this intentionally contains no HLS profile.
 */
internal enum class OpenSubsonicDecoderProfile(val format: String, val contentType: String) {
    MP3_HTTP("mp3", "audio/mpeg"),
}

internal enum class OpenSubsonicStreamVariantMode {
    ORIGINAL,
    LEGACY_STREAM_BEST_EFFORT,
    EXTENSION_TRANSCODE_REQUESTED,
}

internal enum class OpenSubsonicStreamVariantRejection {
    INVALID_IDENTITY,
    INVALID_ACCOUNT_GENERATION,
    INVALID_BITRATE,
    CAPABILITY_NOT_ADVERTISED,
}

internal sealed interface OpenSubsonicStreamVariantPlan {
    class Accepted(val variant: OpenSubsonicStreamVariant) : OpenSubsonicStreamVariantPlan {
        override fun toString(): String = "Accepted($variant)"
    }
    data class Rejected(val reason: OpenSubsonicStreamVariantRejection) : OpenSubsonicStreamVariantPlan
}

/**
 * Open-time intent scoped to a single canonical MediaAsset, never a new music
 * identity. This deliberately is NOT a data class, Parcelable or @Serializable.
 * No credentials, opaque transcodeParams, source ID or authenticated URL fit in
 * this contract. Only Catalog can materialize a private authenticated request.
 */
internal class OpenSubsonicStreamVariant internal constructor(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
    val accountGeneration: Long,
    val mode: OpenSubsonicStreamVariantMode,
    val legacyFormat: String?,
    val legacyMaxBitRateKbps: Int?,
    val decoderProfile: OpenSubsonicDecoderProfile?,
    val maxBitrateBps: Int?,
) {
    override fun toString(): String =
        "OpenSubsonicStreamVariant(mode=$mode, decoderProfile=$decoderProfile)"
}

/**
 * Pure, side-effect-free validation for Playback B12/B13 preview. Permission
 * to *request* transcode is not proof of a positive per-asset decision and is
 * not proof of observed bytes, bitrate, codec or bit-perfect output.
 */
internal object OpenSubsonicStreamVariantPlanner {
    private val ID = Regex("[A-Za-z0-9._:-]{1,256}")
    private val ACCOUNT_ID = Regex("[A-Za-z0-9._-]{1,128}")

    private fun validIdentity(recordingId: String, mediaAssetId: String, providerKey: String): Boolean {
        val account = providerKey.removePrefix("opensubsonic:")
        return recordingId.matches(ID) && mediaAssetId.matches(ID) &&
            providerKey.startsWith("opensubsonic:") && account.matches(ACCOUNT_ID)
    }

    private fun make(
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
        accountGeneration: Long,
        mode: OpenSubsonicStreamVariantMode,
        legacyFormat: String? = null,
        legacyMaxBitRateKbps: Int? = null,
        decoderProfile: OpenSubsonicDecoderProfile? = null,
        maxBitrateBps: Int? = null,
    ): OpenSubsonicStreamVariantPlan {
        if (!validIdentity(recordingId, mediaAssetId, providerKey)) {
            return OpenSubsonicStreamVariantPlan.Rejected(OpenSubsonicStreamVariantRejection.INVALID_IDENTITY)
        }
        if (accountGeneration < 0) {
            return OpenSubsonicStreamVariantPlan.Rejected(
                OpenSubsonicStreamVariantRejection.INVALID_ACCOUNT_GENERATION,
            )
        }
        return OpenSubsonicStreamVariantPlan.Accepted(
            OpenSubsonicStreamVariant(
                recordingId, mediaAssetId, providerKey, accountGeneration,
                mode, legacyFormat, legacyMaxBitRateKbps, decoderProfile, maxBitrateBps,
            ),
        )
    }

    fun original(
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
        accountGeneration: Long,
    ): OpenSubsonicStreamVariantPlan = make(
        recordingId, mediaAssetId, providerKey, accountGeneration,
        OpenSubsonicStreamVariantMode.ORIGINAL, legacyFormat = "raw",
    )

    fun extensionRequested(
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
        accountGeneration: Long,
        confidence: OpenSubsonicStreamConfidence,
        profile: OpenSubsonicDecoderProfile,
        maxBitrateBps: Int,
    ): OpenSubsonicStreamVariantPlan {
        if (confidence != OpenSubsonicStreamConfidence.ADVERTISED &&
            confidence != OpenSubsonicStreamConfidence.VERIFIED
        ) {
            return OpenSubsonicStreamVariantPlan.Rejected(
                OpenSubsonicStreamVariantRejection.CAPABILITY_NOT_ADVERTISED,
            )
        }
        if (maxBitrateBps !in 32_000..512_000) {
            return OpenSubsonicStreamVariantPlan.Rejected(OpenSubsonicStreamVariantRejection.INVALID_BITRATE)
        }
        return make(
            recordingId, mediaAssetId, providerKey, accountGeneration,
            OpenSubsonicStreamVariantMode.EXTENSION_TRANSCODE_REQUESTED,
            decoderProfile = profile, maxBitrateBps = maxBitrateBps,
        )
    }

    fun legacyBestEffort(
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
        accountGeneration: Long,
        maxBitRateKbps: Int,
    ): OpenSubsonicStreamVariantPlan {
        if (maxBitRateKbps !in 32..512) {
            return OpenSubsonicStreamVariantPlan.Rejected(OpenSubsonicStreamVariantRejection.INVALID_BITRATE)
        }
        return make(
            recordingId, mediaAssetId, providerKey, accountGeneration,
            OpenSubsonicStreamVariantMode.LEGACY_STREAM_BEST_EFFORT,
            legacyFormat = "mp3", legacyMaxBitRateKbps = maxBitRateKbps,
        )
    }
}
