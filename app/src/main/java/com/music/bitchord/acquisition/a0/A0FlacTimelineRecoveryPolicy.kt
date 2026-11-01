package com.music.bitchord.acquisition.a0

import kotlin.math.abs

/** FLAC timestamp recovery never trusts MediaExtractor packet count as frames. */
internal object A0FlacTimelineRecoveryPolicy {
    enum class Verdict {
        NOT_NEEDED, VERIFIED_ENCODED_FRAME_SEQUENCE,
        NO_INDEPENDENT_STREAMINFO, ENCODED_FRAME_PACKET_UNREADABLE,
        ENCODED_FRAME_SEQUENCE_INVALID, FRAME_SAMPLE_COVERAGE_MISMATCH,
        STREAMINFO_DURATION_MISMATCH, INSUFFICIENT_DEMUX_BYTES,
        EXCESSIVE_RESETS,
    }
    fun assess(
        resetEvents:Int,
        maximumRegressionMs:Long,
        fixedBlockSize:Int?,
        totalPcmSamples:Long?,
        sampleRateHz:Int?,
        durationMillis:Long,
        frameProblem:A0FlacFrameSequenceEvidence.Problem,
        frameSamples:Long,
        frameCount:Int,
        demuxedBytes:Long,
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
        if(frameProblem!=A0FlacFrameSequenceEvidence.Problem.NONE)
            return if(frameProblem==A0FlacFrameSequenceEvidence.Problem.PACKET_UNREADABLE)
                Verdict.ENCODED_FRAME_PACKET_UNREADABLE
                else Verdict.ENCODED_FRAME_SEQUENCE_INVALID
        if(frameCount<2 || frameSamples!=total)
            return Verdict.FRAME_SAMPLE_COVERAGE_MISMATCH
        if(fileBytes<44L || demuxedBytes<fileBytes/2L ||
            demuxedBytes>fileBytes)
            return Verdict.INSUFFICIENT_DEMUX_BYTES
        val expectedDurationMs=total*1000L/rate
        if(expectedDurationMs !in 15_000L..7_200_000L ||
            durationMillis !in 15_000L..7_200_000L ||
            abs(expectedDurationMs-durationMillis)>2000L)
            return Verdict.STREAMINFO_DURATION_MISMATCH
        return Verdict.VERIFIED_ENCODED_FRAME_SEQUENCE
    }
}
