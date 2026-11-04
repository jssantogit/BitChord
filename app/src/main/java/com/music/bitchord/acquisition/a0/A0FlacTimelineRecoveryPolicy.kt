package com.music.bitchord.acquisition.a0

import kotlin.math.abs

/**
 * Android FLAC PTS workarounds require a VERIFIED LOCAL file scan, not trust
 * in the Android demux output after it starts returning PTS=0. This check
 * does not replace real MediaExtractor EOS, quality, A3 or cancellation.
 */
internal object A0FlacTimelineRecoveryPolicy {
    enum class Verdict {
        NOT_NEEDED, VERIFIED_LOCAL_FLAC_SEQUENCE,
        NO_INDEPENDENT_STREAMINFO, INDEPENDENT_FILE_UNVERIFIED,
        FRAME_SAMPLE_COVERAGE_MISMATCH, STREAMINFO_DURATION_MISMATCH,
        INCOMPLETE_FILE_EVIDENCE, EXCESSIVE_RESETS,
    }
    fun assess(
        resetEvents:Int,
        maximumRegressionMs:Long,
        fixedBlockSize:Int?,
        totalPcmSamples:Long?,
        sampleRateHz:Int?,
        durationMillis:Long,
        localStatus:A0FlacWholeFileEvidence.Status?,
        localSamples:Long,
        localFrames:Int,
        locallyScannedBytes:Long,
        fileBytes:Long,
    ):Verdict {
        if(resetEvents==0)return Verdict.NOT_NEEDED
        if(resetEvents !in 1..64 || maximumRegressionMs !in 1..60_000)
            return Verdict.EXCESSIVE_RESETS
        fixedBlockSize?.takeIf {it in 16..65_535}
            ?:return Verdict.NO_INDEPENDENT_STREAMINFO
        val total=totalPcmSamples?.takeIf {it>0}
            ?:return Verdict.NO_INDEPENDENT_STREAMINFO
        val rate=sampleRateHz?.takeIf {it in 8_000..384_000}
            ?:return Verdict.NO_INDEPENDENT_STREAMINFO
        if(localStatus!=A0FlacWholeFileEvidence.Status.VERIFIED)
            return Verdict.INDEPENDENT_FILE_UNVERIFIED
        if(localFrames<2 || localSamples!=total)
            return Verdict.FRAME_SAMPLE_COVERAGE_MISMATCH
        // Validating all local bytes matters more than comparing opaque
        // MediaExtractor packet sizes against the file size.
        if(fileBytes<44L || locallyScannedBytes!=fileBytes)
            return Verdict.INCOMPLETE_FILE_EVIDENCE
        val independentDuration=total*1000L/rate
        if(independentDuration !in 15_000L..7_200_000L ||
            durationMillis !in 15_000L..7_200_000L ||
            abs(independentDuration-durationMillis)>2000L)
            return Verdict.STREAMINFO_DURATION_MISMATCH
        return Verdict.VERIFIED_LOCAL_FLAC_SEQUENCE
    }
}
