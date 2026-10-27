package com.music.bitchord.acquisition.a0

/**
 * Coalesces noisy progress notifications on a monotonic clock supplied by
 * the caller. First event is immediate; a clock reset also permits one.
 * Never delays the final candidate list or affects network collection.
 */
internal class A0ProgressUpdateGate(private val intervalMillis:Long=700L) {
    init { require(intervalMillis>0) }
    private var lastUpdateMillis:Long?=null

    @Synchronized
    fun shouldPublish(nowMillis:Long):Boolean {
        val previous=lastUpdateMillis
        if(previous!=null && nowMillis>=previous &&
            nowMillis-previous<intervalMillis) return false
        lastUpdateMillis=nowMillis
        return true
    }
}
