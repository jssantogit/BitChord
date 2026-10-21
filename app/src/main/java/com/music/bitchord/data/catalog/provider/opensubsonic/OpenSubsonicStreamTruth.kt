package com.music.bitchord.data.catalog.provider.opensubsonic

/**
 * Stream truth is deliberately narrower than playback truth. Neither a positive
 * server decision nor HTTP bytes proves a decoder output, audible playback,
 * effective bitrate or bit-perfect transport.
 */
internal enum class OpenSubsonicNegotiationEvidence {
    UNKNOWN,
    SERVER_DECISION,
    DENIED,
}

internal enum class OpenSubsonicTransportStage {
    NOT_OPENED,
    HEADERS_RECEIVED,
    BYTES_RECEIVED,
    CLOSED,
    FAILED,
}

/** Credential-free, bounded projection for Playback policy and diagnostics. */
internal class OpenSubsonicStreamTruthSnapshot internal constructor(
    val requestedMode: OpenSubsonicStreamVariantMode,
    val requestedMaxBitrateBps: Int?,
    val negotiation: OpenSubsonicNegotiationEvidence,
    val transportStage: OpenSubsonicTransportStage,
    val httpStatusCode: Int?,
    val reportedMimeType: String?,
    val reportedLength: Long?,
    val receivedByteCount: Long,
) {
    // Unmeasured by Catalog. Leave unknown rather than interpreting headers,
    // a successful POST, or HTTP 200/206 as audio output.
    val actualCodec: String? get() = null
    val actualBitrateBps: Int? get() = null
    val audible: Boolean? get() = null
    val bitPerfect: Boolean? get() = null

    override fun toString(): String =
        "OpenSubsonicStreamTruth(mode=$requestedMode, negotiation=$negotiation, " +
            "transport=$transportStage, status=$httpStatusCode, mime=$reportedMimeType, " +
            "length=$reportedLength, bytes=$receivedByteCount)"
}

/**
 * Per-open attempt generation invalidates callbacks from replaced or cancelled
 * sessions. No request identity, endpoint, headers, credentials or opaque token
 * is saved in the observable snapshot.
 */
internal class OpenSubsonicStreamTruthTracker {
    private var sequence: Long = 0
    private var active: Long = 0
    private var activeMode = OpenSubsonicStreamVariantMode.ORIGINAL
    private var maxBitrate: Int? = null
    private var evidence = OpenSubsonicNegotiationEvidence.UNKNOWN
    private var stage = OpenSubsonicTransportStage.NOT_OPENED
    private var status: Int? = null
    private var mime: String? = null
    private var byteLength: Long? = null
    private var bytes: Long = 0

    @get:Synchronized
    val snapshot: OpenSubsonicStreamTruthSnapshot
        get() = OpenSubsonicStreamTruthSnapshot(
            activeMode, maxBitrate, evidence, stage, status, mime, byteLength, bytes,
        )

    @Synchronized
    fun begin(variant: OpenSubsonicStreamVariant): Long {
        // A unique generation also handles wraparound without accepting old
        // callbacks when the sequence rolls over.
        sequence = if (sequence == Long.MAX_VALUE) 1 else sequence + 1
        active = sequence
        activeMode = variant.mode
        maxBitrate = variant.maxBitrateBps
            ?: variant.legacyMaxBitRateKbps?.times(1_000)
        evidence = OpenSubsonicNegotiationEvidence.UNKNOWN
        stage = OpenSubsonicTransportStage.NOT_OPENED
        status = null
        mime = null
        byteLength = null
        bytes = 0
        return active
    }

    @Synchronized
    fun onDecision(attempt: Long, outcome: OpenSubsonicTranscodeDecisionOutcome) {
        if (attempt != active || stage == OpenSubsonicTransportStage.FAILED) return
        if (activeMode != OpenSubsonicStreamVariantMode.EXTENSION_TRANSCODE_REQUESTED) return
        evidence = when (outcome) {
            is OpenSubsonicTranscodeDecisionOutcome.Ready ->
                OpenSubsonicNegotiationEvidence.SERVER_DECISION
            is OpenSubsonicTranscodeDecisionOutcome.Rejected ->
                OpenSubsonicNegotiationEvidence.DENIED
        }
    }

    @Synchronized
    fun onHeaders(
        attempt: Long,
        statusCode: Int,
        responseHeaders: Map<String, List<String>>,
        length: Long?,
    ) {
        if (attempt != active || stage == OpenSubsonicTransportStage.FAILED ||
            stage == OpenSubsonicTransportStage.CLOSED) return
        status = statusCode.takeIf { it in 100..599 }
        // A claimed MIME is only a header: never equate it with decoded codec.
        mime = responseHeaders.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it.length in 1..64 && it.matches(Regex("[a-z0-9][a-z0-9!#\u0024&^_.+-]*/[a-z0-9][a-z0-9!#\u0024&^_.+-]*")) }
        byteLength = length?.takeIf { it >= 0L }
        stage = OpenSubsonicTransportStage.HEADERS_RECEIVED
    }

    @Synchronized
    fun onBytes(attempt: Long, count: Int) {
        if (attempt != active || count <= 0 || stage !in setOf(
                OpenSubsonicTransportStage.HEADERS_RECEIVED,
                OpenSubsonicTransportStage.BYTES_RECEIVED,
            )) return
        bytes = if (Long.MAX_VALUE - bytes < count) Long.MAX_VALUE else bytes + count
        stage = OpenSubsonicTransportStage.BYTES_RECEIVED
    }

    @Synchronized
    fun onFailure(attempt: Long) {
        if (attempt == active && stage != OpenSubsonicTransportStage.CLOSED) {
            stage = OpenSubsonicTransportStage.FAILED
        }
    }

    @Synchronized
    fun onClose(attempt: Long) {
        if (attempt == active && stage != OpenSubsonicTransportStage.FAILED) {
            stage = OpenSubsonicTransportStage.CLOSED
        }
    }
}
