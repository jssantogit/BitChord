package com.music.bitchord.playback

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicNegotiationEvidence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransportStage
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics
import java.util.Locale

/** Input format reported by Media3, NOT proof that decoded frames reached the output. */
internal enum class CanonicalRemoteDecoderInputEvidence {
    MP3, FLAC, AAC, UNKNOWN;

    companion object {
        fun fromMime(mime: String?): CanonicalRemoteDecoderInputEvidence = when (mime) {
            "audio/mpeg" -> MP3
            "audio/flac", "audio/x-flac" -> FLAC
            "audio/mp4a-latm", "audio/aac" -> AAC
            else -> UNKNOWN
        }
    }
}

/**
 * B14-B18: an observation carries exactly its evidence boundary.
 * No track IDs, provider identifiers, URLs, headers, MIME text, tokens, names
 * or account revisions can enter diagnostic metadata.
 */
internal enum class CanonicalRemoteQualityEvidenceStage {
    PREPARED,
    TRANSPORT_OPENED, // DataSource.open returned; neither bytes nor decoded audio.
    CATALOG_TRUTH, // Catalog C12 reports decision/HTTP stage; never decoder truth.
    REJECTED,
    FAILED,
    CANCELLED,
    CLOSED,
    DECODER_INPUT_REPORTED, // Media3 format callback, not proof of output or audibility.
    DECODER_INITIALIZED, // Media3 initialized decoder; output still unverified.
}

internal data class CanonicalRemoteQualityEvidence(
    val stage: CanonicalRemoteQualityEvidenceStage,
    val attemptId: String? = null,
    val mode: CanonicalRemoteStreamMode? = null,
    val rejection: CanonicalRemoteStreamRejection? = null,
    val requestedBitrateBps: Int? = null,
    val network: CanonicalRemoteNetwork? = null,
    val negotiation: OpenSubsonicNegotiationEvidence? = null,
    val transport: OpenSubsonicTransportStage? = null,
    val decoderInput: CanonicalRemoteDecoderInputEvidence? = null,
) {
    fun toDiagnosticMetadata(): Map<String, String> = buildMap {
        attemptId?.let { put("qualityAttemptId", it) }
        put("evidenceStage", stage.name.lowercase(Locale.ROOT))
        mode?.let { put("requestMode", it.name.lowercase(Locale.ROOT)) }
        rejection?.let { put("rejection", it.name.lowercase(Locale.ROOT)) }
        requestedBitrateBps?.takeIf { it in 1..512_000 }
            ?.let { put("requestedCeilingBps", it.toString()) }
        network?.let { put("physicalTransport", it.name.lowercase(Locale.ROOT)) }
        negotiation?.let { put("catalogNegotiation", it.name.lowercase(Locale.ROOT)) }
        transport?.let { put("catalogTransport", it.name.lowercase(Locale.ROOT)) }
        decoderInput?.let { put("media3InputFamily", it.name.lowercase(Locale.ROOT)) }
        // Intentionally absent: actual bitrate, decoded output, audibility, bit-perfect.
    }
}

/** Adapter to the existing diagnostics pipeline, not a second event store. */
internal object CanonicalRemoteQualityDiagnostics {
    fun record(observation: CanonicalRemoteQualityEvidence) {
        Diagnostics.event(
            category = DiagnosticCategory.PLAYER,
            name = "remote_quality_" + observation.stage.name.lowercase(Locale.ROOT),
            metadata = observation.toDiagnosticMetadata(),
        )
    }
}
