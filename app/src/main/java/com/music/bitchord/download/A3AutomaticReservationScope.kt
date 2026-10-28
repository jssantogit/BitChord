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

    fun <T> run(jobIds:List<String>,work:()->T):Outcome<T> {
        var completed=false
        try {
            val value=work()
            completed=true
            return Outcome(value,
                ledger.releaseUntransferredAutomaticBatch(jobIds))
        }finally {
            // Exception between the A3 reserve and the first P/F must not
            // poison an otherwise retryable recording. Never swallow the
            // original failure unless this durable release itself fails.
            if(!completed)ledger.releaseUntransferredAutomaticBatch(jobIds)
        }
    }
}
