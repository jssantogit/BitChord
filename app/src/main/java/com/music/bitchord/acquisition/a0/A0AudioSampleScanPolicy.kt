package com.music.bitchord.acquisition.a0

import kotlin.math.abs

/**
 * An empty terminal extractor packet does not prove media corruption. We may
 * treat it as EOS only after positive encoded samples already covered the
 * independently observed duration. A mid-stream gap or missing audio cannot
 * authorize an incomplete scan. No peer metadata enters this policy.
 */
internal object A0AudioSampleScanPolicy {
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
