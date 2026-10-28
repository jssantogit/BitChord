package com.music.bitchord.acquisition.a0

/**
 * Local sequential retry *state*, NOT a Soulseek client or a transfer gate.
 *
 * This pure state machine is an input to the future #184 executor. Only
 * already-confirmed metadata-equivalent alternative peers may be counted.
 * User opt-in defaults OFF, and actual P/F is never accessible here.
 */
internal class A0AutoRetrySequencer(
    alternativeCount:Int,
    private val optIn:Boolean=false,
    private val equivalentClaimsConfirmed:Boolean=false,
) {
    init { require(alternativeCount in 0..3) }
    private val alternatives=alternativeCount
    private var attempted=0
    private var inFlight:Attempt?=null
    private var cancelled=false
    private var succeeded=false
    private var serial=0L

    class Attempt internal constructor(
        val alternativeIndex:Int,
        internal val serial:Long,
    ) {
        override fun toString()="AutoRetryAttempt(redacted)"
    }

    val canTransfer:Boolean get()=false
    val attemptsStarted:Int @Synchronized get()=attempted
    val isComplete:Boolean @Synchronized get()=
        cancelled || succeeded || attempted>=alternatives && inFlight==null

    @Synchronized
    fun begin():Attempt? {
        if(!optIn || !equivalentClaimsConfirmed || cancelled || succeeded ||
            inFlight!=null || attempted>=alternatives)return null
        val attempt=Attempt(attempted,++serial)
        inFlight=attempt
        attempted++
        return attempt
    }

    @Synchronized
    fun complete(attempt:Attempt,success:Boolean):Boolean {
        if(inFlight!==attempt || cancelled || succeeded)return false
        inFlight=null
        if(success)succeeded=true
        return true
    }

    @Synchronized
    fun cancel() {
        cancelled=true
        inFlight=null
    }

    override fun toString()="A0AutoRetrySequencer(aggregate-only)"
}
