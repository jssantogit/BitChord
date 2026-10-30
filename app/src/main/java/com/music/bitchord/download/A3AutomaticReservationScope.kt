package com.music.bitchord.download

/**
 * An automatic album is durably reserved before it may open sockets. This
 * scope closes that reservation after discovery even if authorization is
 * revoked or search fails exceptionally.
 *
 * A3 itself makes the final decision: only an exact group whose every slot
 * is still REQUESTED can be released. Once any peer transfer began, the
 * physical duplicate fence remains forever until manual reconciliation.
 */
internal class A3AutomaticReservationScope(private val ledger:A3AcquisitionLedger) {
    data class Outcome<T>(
        val value:T,
        val unusedReservationReleased:Boolean,
    )

    fun <T> run(
        jobIds:List<String>,
        /** Also invoked for exceptional exits, but only AFTER A3 commit. */
        onDurablyReleased:()->Unit={},
        work:()->T,
    ):Outcome<T> {
        var completed=false
        fun releaseAndSignal():Boolean {
            val released=ledger.releaseUntransferredAutomaticBatch(jobIds)
            if(released)onDurablyReleased()
            return released
        }
        try {
            val value=work()
            completed=true
            return Outcome(value,releaseAndSignal())
        }finally {
            // Exceptions after A3 reservation but before P/F must not poison
            // process-local admission. The same durable A3 proof is required
            // whether work returned normally or threw.
            if(!completed)releaseAndSignal()
        }
    }
}
