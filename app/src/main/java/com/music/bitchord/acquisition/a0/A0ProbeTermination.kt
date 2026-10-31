package com.music.bitchord.acquisition.a0

import kotlin.math.abs

/** Reasons for an incomplete local demux scan; no filenames, paths or audio. */
internal object A0ProbeTermination {
    enum class Reason {
        COMPLETE, SAMPLE_CAP, TIME_LIMIT, CANCELLED, NO_END_OF_STREAM,
        TOO_FEW_SAMPLES, NO_SAMPLE_TIMESTAMP, DURATION_NOT_COVERED,
    }
    fun classify(
        reachedEos:Boolean,
        sampleCount:Int,
        lastTimestampUs:Long,
        expectedDurationMs:Long,
        capReached:Boolean,
        timeExpired:Boolean,
        cancelled:Boolean,
    ):Reason=when {
        !reachedEos && cancelled -> Reason.CANCELLED
        !reachedEos && capReached -> Reason.SAMPLE_CAP
        !reachedEos && timeExpired -> Reason.TIME_LIMIT
        !reachedEos -> Reason.NO_END_OF_STREAM
        sampleCount<2 -> Reason.TOO_FEW_SAMPLES
        lastTimestampUs<0 -> Reason.NO_SAMPLE_TIMESTAMP
        abs(expectedDurationMs-lastTimestampUs/1000L)>3000L ->
            Reason.DURATION_NOT_COVERED
        else -> Reason.COMPLETE
    }
}
