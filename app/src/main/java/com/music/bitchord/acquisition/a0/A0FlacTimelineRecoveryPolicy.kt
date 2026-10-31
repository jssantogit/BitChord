package com.music.bitchord.acquisition.a0

import kotlin.math.abs

/**
 * Backup to Android FLAC MediaExtractor PTS only when two independent LOCAL
 * observations agree: STREAMINFO's frame count and the Android measured
 * duration, after a complete positive non-encrypted audio packet walk.
 *
 * This is not a PCM decode or music identity check. It cannot override
 * transfer byte checks, quality/duration gates, cancellation or A3.
 */
internal object A0FlacTimelineRecoveryPolicy {
    enum class Verdict {
        NOT_NEEDED, VERIFIED_FIXED_BLOCK_COUNT,
        NO_INDEPENDENT_STREAMINFO, FRAME_COUNT_MISMATCH,
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
        positiveDemuxPackets:Int,
        demuxedBytes:Long,
        fileBytes:Long,
    ):Verdict {
        if(resetEvents==0)return Verdict.NOT_NEEDED
        if(resetEvents !in 1..64 || maximumRegressionMs !in 1..60_000)
            return Verdict.EXCESSIVE_RESETS
        val block=fixedBlockSize?.takeIf {it in 16..65_535}
            ?:return Verdict.NO_INDEPENDENT_STREAMINFO
        val total=totalPcmSamples?.takeIf {it>0}
            ?:return Verdict.NO_INDEPENDENT_STREAMINFO
        val rate=sampleRateHz?.takeIf {it in 8_000..384_000}
            ?:return Verdict.NO_INDEPENDENT_STREAMINFO
        val expectedFrames=(total+block-1)/block
        if(expectedFrames !in 2L..400_000L ||
            positiveDemuxPackets.toLong()!=expectedFrames)
            return Verdict.FRAME_COUNT_MISMATCH
        // Do not accept tiny truncated extracts padded with matching frame
        // timestamps; compressed packet bytes must cover most of the
        // locally received file. Metadata-heavy files remain manual.
        if(fileBytes<44L || demuxedBytes<fileBytes/2L ||
            demuxedBytes>fileBytes)
            return Verdict.INSUFFICIENT_DEMUX_BYTES
        val expectedDurationMs=total*1000L/rate
        if(expectedDurationMs !in 15_000L..7_200_000L ||
            durationMillis !in 15_000L..7_200_000L ||
            abs(expectedDurationMs-durationMillis)>2000L)
            return Verdict.STREAMINFO_DURATION_MISMATCH
        return Verdict.VERIFIED_FIXED_BLOCK_COUNT
    }
}
