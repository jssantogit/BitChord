package com.music.bitchord.acquisition.a0

import kotlin.math.abs

/**
 * Pure, post-transfer technical check for a FUTURE opt-in automatic flow.
 * Evidence must come from a real local audio extractor, not Soulseek claims.
 *
 * A valid digest checks transport bytes, NOT musical identity, provenance or
 * full decode. This gate never publishes, picks a peer, starts P/F, or grants
 * download authority; actual Android extractor wiring is still required.
 */
internal object A0AutoPostTransferTechnicalGate {
    enum class Status { TECHNICALLY_PLAUSIBLE, REVIEW, REJECT }
    enum class Reason {
        MISSING_DECODER_EVIDENCE, INCOMPLETE_DECODE,
        BYTE_COUNT_MISMATCH, UNSUPPORTED_CONTAINER,
        DURATION_MISSING, DURATION_MISMATCH, QUALITY_MISMATCH,
        INVALID_TECHNICAL_METADATA,
    }

    data class Decoded(
        val codec:A0CanonicalAcquisitionIntent.Codec?,
        val extension:String,
        val durationMillis:Long?,
        val bitrateKbps:Int?,
        val sampleRateHz:Int?,
        val bitDepth:Int?,
        // TRUE only when the future trusted local extractor completed the
        // required parsing/decoding, not merely read an ID3/header signature.
        val completed:Boolean,
    ) {
        override fun toString()="DecodedAudioEvidence(redacted)"
    }

    data class Assessment(val status:Status,val reason:Reason?) {
        val canPublishAutomatically:Boolean get()=false
        val provesMusicIdentity:Boolean get()=false
    }

    fun assess(
        profile:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        requestedExtension:String,
        expectedDurationSeconds:Int?,
        receiptByteCount:Long,
        actualByteCount:Long,
        decoded:Decoded?,
    ):Assessment {
        if(receiptByteCount<44L || actualByteCount!=receiptByteCount)
            return Assessment(Status.REJECT,Reason.BYTE_COUNT_MISMATCH)
        if(decoded==null)return Assessment(Status.REVIEW,Reason.MISSING_DECODER_EVIDENCE)
        if(!decoded.completed)
            return Assessment(Status.REVIEW,Reason.INCOMPLETE_DECODE)
        val codec=decoded.codec
            ?: return Assessment(Status.REJECT,Reason.UNSUPPORTED_CONTAINER)
        val actualExtension=decoded.extension.trimStart('.')
            .lowercase(java.util.Locale.ROOT)
        val expected=requestedExtension.trimStart('.')
            .lowercase(java.util.Locale.ROOT)
        val canonical=when(codec) {
            A0CanonicalAcquisitionIntent.Codec.FLAC -> "flac"
            A0CanonicalAcquisitionIntent.Codec.PCM_WAV -> "wav"
            A0CanonicalAcquisitionIntent.Codec.MP3 -> "mp3"
            A0CanonicalAcquisitionIntent.Codec.AAC_ADTS -> "aac"
        }
        if(actualExtension!=canonical || expected!=canonical)
            return Assessment(Status.REJECT,Reason.UNSUPPORTED_CONTAINER)
        val expectedDuration=expectedDurationSeconds?.takeIf {it in 15..7200}
            ?: return Assessment(Status.REVIEW,Reason.DURATION_MISSING)
        val actualDuration=decoded.durationMillis?.takeIf {
            it in 15_000L..7_200_000L
        } ?: return Assessment(Status.REVIEW,Reason.DURATION_MISSING)
        if(abs(actualDuration-expectedDuration*1000L)>8_000L)
            return Assessment(Status.REJECT,Reason.DURATION_MISMATCH)
        // A 24-bit/96kHz stereo WAV naturally runs at 4608 kbps, and
        // compressed lossless FLAC can exceed 1536 kbps too. The lossy cap
        // does not apply to PCM/lossless. Reject absurd decoder readings
        // while preserving the strict MP3 ceiling of 320 kbps.
        val bitrateValid=decoded.bitrateKbps?.let { rate ->
            when(codec) {
                A0CanonicalAcquisitionIntent.Codec.MP3 -> rate in 32..320
                A0CanonicalAcquisitionIntent.Codec.AAC_ADTS -> rate in 32..1536
                A0CanonicalAcquisitionIntent.Codec.FLAC,
                A0CanonicalAcquisitionIntent.Codec.PCM_WAV -> rate in 1..100_000
            }
        } ?: true
        if((decoded.sampleRateHz!=null &&
                decoded.sampleRateHz !in 8000..384000) ||
            (decoded.bitDepth!=null && decoded.bitDepth !in 8..32) ||
            !bitrateValid)
            return Assessment(Status.REJECT,Reason.INVALID_TECHNICAL_METADATA)

        // FLAC/WAV lossless provenance needs actual local PCM properties,
        // not just the extension. Missing measurements stay review-only.
        if(codec in setOf(A0CanonicalAcquisitionIntent.Codec.FLAC,
                A0CanonicalAcquisitionIntent.Codec.PCM_WAV) &&
            (decoded.sampleRateHz==null || decoded.sampleRateHz<44_100 ||
                decoded.bitDepth==null || decoded.bitDepth<16))
            return Assessment(Status.REVIEW,Reason.QUALITY_MISMATCH)

        val checked=A0DownloadQualityPolicy.evaluate(
            profile,
            A0DownloadQualityPolicy.Offer(codec,canonical,
                decoded.bitrateKbps,decoded.sampleRateHz,decoded.bitDepth),
            setOf(A0CanonicalAcquisitionIntent.Codec.FLAC,
                A0CanonicalAcquisitionIntent.Codec.PCM_WAV,
                A0CanonicalAcquisitionIntent.Codec.MP3,
                A0CanonicalAcquisitionIntent.Codec.AAC_ADTS)
        )
        return when(checked.status) {
            A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED ->
                Assessment(Status.TECHNICALLY_PLAUSIBLE,null)
            A0DownloadQualityPolicy.Status.NOT_CONSTRAINED,
            A0DownloadQualityPolicy.Status.INCONCLUSIVE_MANUAL ->
                Assessment(Status.REVIEW,Reason.QUALITY_MISMATCH)
            else -> Assessment(Status.REJECT,Reason.QUALITY_MISMATCH)
        }
    }
}
