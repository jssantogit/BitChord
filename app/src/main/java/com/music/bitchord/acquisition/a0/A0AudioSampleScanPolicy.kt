package com.music.bitchord.acquisition.a0

import kotlin.math.abs

/**
 * An empty terminal extractor packet does not prove media corruption. We may
 * treat it as EOS only after positive encoded samples already covered the
 * independently observed duration. A mid-stream gap or missing audio cannot
 * authorize an incomplete scan. No peer metadata enters this policy.
 */
internal object A0AudioSampleScanPolicy {
    fun skipLeadingEmpty(
        positiveSamples:Int,
        emptyLeadingAlreadySkipped:Int,
        belongsToAudioTrack:Boolean,
        extractorHasMore:Boolean,
    ):Boolean = positiveSamples==0 &&
        emptyLeadingAlreadySkipped in 0..1 &&
        belongsToAudioTrack && extractorHasMore

    enum class TimestampVerdict {
        OK, TOLERATE_SINGLE_SMALL_FLAC_BACKSTEP, NEGATIVE, REGRESSION,
    }

    /**
     * One anomalous FLAC extractor presentation timestamp cannot by itself
     * invalidate a complete local demux. No general reordering permission:
     * at most ONE backstep of <=100 ms, never for other codecs.
     * EOS/duration, full samples, local headers and A3 still have to pass.
     */
    fun timestampVerdict(
        nextUs:Long,
        maximumPreviouslySeenUs:Long,
        toleratedBacksteps:Int,
        isFlac:Boolean,
    ):TimestampVerdict = when {
        nextUs<0 -> TimestampVerdict.NEGATIVE
        maximumPreviouslySeenUs<0 || nextUs>=maximumPreviouslySeenUs ->
            TimestampVerdict.OK
        isFlac && toleratedBacksteps==0 &&
            maximumPreviouslySeenUs-nextUs in 1L..100_000L ->
            TimestampVerdict.TOLERATE_SINGLE_SMALL_FLAC_BACKSTEP
        else -> TimestampVerdict.REGRESSION
    }

    fun acceptTerminalEmpty(
        positiveSamples:Int,
        lastSampleUs:Long,
        durationMillis:Long,
        extractorAtEos:Boolean,
    ):Boolean =
        extractorAtEos &&
        positiveSamples>=2 &&
        lastSampleUs>=0 &&
        durationMillis in 15_000L..7_200_000L &&
        abs(durationMillis-lastSampleUs/1000L)<=3_000L
}
