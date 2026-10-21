package com.music.bitchord.data.catalog.provider.opensubsonic

/**
 * Confidence is about evidence, not about whether a playback session can start.
 * ADVERTISED never means a codec, bitrate, MediaAsset or decoded byte was proven.
 */
internal enum class OpenSubsonicStreamConfidence {
    UNKNOWN, UNSUPPORTED, ADVERTISED, VERIFIED
}

internal enum class OpenSubsonicStreamMechanism {
    TRADITIONAL_STREAM_BEST_EFFORT, EXTENSION_TRANSCODING_V1
}

internal enum class OpenSubsonicStreamEvidenceBasis {
    NO_RESPONSE, PROTOCOL_SPEC, MANIFEST_ABSENT, MANIFEST_V1,
    VERSION_INCOMPATIBLE, MALFORMED_OR_INCOMPLETE, MEDIA_DECISION
}

/**
 * Safe to project into a read-only consumer. Source IDs, auth and any decoded
 * transcodeParams are never part of this shape or its string representation.
 */
internal class OpenSubsonicStreamCapability internal constructor(
    val mechanism: OpenSubsonicStreamMechanism,
    val confidence: OpenSubsonicStreamConfidence,
    val basis: OpenSubsonicStreamEvidenceBasis,
    private val verifiedMediaAssetId: String? = null,
    private val verifiedDecoderProfileId: String? = null,
) {
    fun isVerifiedFor(mediaAssetId: String, decoderProfileId: String): Boolean =
        confidence == OpenSubsonicStreamConfidence.VERIFIED &&
            mediaAssetId.isNotBlank() && decoderProfileId.isNotBlank() &&
            verifiedMediaAssetId == mediaAssetId &&
            verifiedDecoderProfileId == decoderProfileId

    /** Only a valid positive media-specific decision may upgrade an advertised extension. */
    internal fun withVerifiedMediaDecision(
        mediaAssetId: String,
        decoderProfileId: String,
    ): OpenSubsonicStreamCapability? {
        if (mechanism != OpenSubsonicStreamMechanism.EXTENSION_TRANSCODING_V1 ||
            confidence != OpenSubsonicStreamConfidence.ADVERTISED ||
            mediaAssetId.isBlank() || decoderProfileId.isBlank()
        ) return null
        return OpenSubsonicStreamCapability(
            mechanism, OpenSubsonicStreamConfidence.VERIFIED,
            OpenSubsonicStreamEvidenceBasis.MEDIA_DECISION,
            verifiedMediaAssetId = mediaAssetId,
            verifiedDecoderProfileId = decoderProfileId,
        )
    }

    override fun toString(): String =
        "OpenSubsonicStreamCapability(mechanism=$mechanism, confidence=$confidence, basis=$basis)"
}

/**
 * An in-memory lease for one account configuration and caller-owned generation.
 * Deliberately not a data/Serializable class: the private config comparison is
 * only allowed inside Catalog, never exposed to Playback or diagnostics.
 */
internal class OpenSubsonicStreamCapabilitySnapshot internal constructor(
    val generation: Long,
    val traditional: OpenSubsonicStreamCapability,
    val transcoding: OpenSubsonicStreamCapability,
    private val configurationIsCurrent: (OpenSubsonicAccountConfig?) -> Boolean,
) {
    fun matches(generation: Long, savedConfig: OpenSubsonicAccountConfig?): Boolean =
        generation == this.generation && configurationIsCurrent(savedConfig)

    override fun toString(): String =
        "OpenSubsonicStreamCapabilitySnapshot(generation=$generation, traditional=$traditional, transcoding=$transcoding)"
}
