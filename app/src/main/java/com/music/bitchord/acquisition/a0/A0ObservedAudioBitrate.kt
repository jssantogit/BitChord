package com.music.bitchord.acquisition.a0

/**
 * Observed encoded audio payload rate, not an encoder setting and never a
 * claim about the original master. Source must be the completed demux scan
 * of a private receipt, not peer metadata or the Ogg container file size.
 */
internal object A0ObservedAudioBitrate {
    fun fromDemuxedSamples(sampleBytes:Long,durationMillis:Long):Int? {
        if(sampleBytes !in 44L..A0ObservedTransferLimits.MAX_AUDIO_BYTES ||
            durationMillis !in 15_000L..7_200_000L)return null
        // bytes*8 / milliseconds is numerically kbps; round the observed
        // mean to the nearest whole kbps to avoid truncation at 160/192.
        val rate=(sampleBytes*8L + durationMillis/2L)/durationMillis
        return rate.toInt().takeIf { rate in 32L..512L }
    }
}
