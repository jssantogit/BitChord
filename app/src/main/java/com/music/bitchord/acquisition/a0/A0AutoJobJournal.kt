package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.A3AcquisitionLedger

/**
 * Exactly one opaque A3 journal job for an AUTO track, regardless of how many
 * equivalent Soulseek sources were attempted. Does not store peer paths,
 * names, credentials or private receipts. The caller must have already
 * performed the canonical candidate preflight before constructing a job.
 *
 * All publication goes through A3AndroidAcquisitionLedger: this only drives
 * the durable state machine and NEVER marks a file AVAILABLE itself.
 */
internal class A0AutoJobJournal(
    private val ledger:A3AcquisitionLedger,
    private val id:String,
    private val reservedAutomatic:Boolean=false,
) {
    private var created=false
    private var uncertain=false
    private var rolledBack=false

    val needsReconciliation:Boolean @Synchronized get()=uncertain

    @Synchronized
    fun beginTransfer() {
        if(!created) {
            if(reservedAutomatic) {
                val existing=ledger.get(id)
                check(existing?.phase==A3AcquisitionLedger.Phase.REQUESTED &&
                    existing.autoRecordingDigest!=null &&
                    existing.autoReleaseDigest!=null) {
                    "A3 automatic transfer is not bound to a durable reservation"
                }
            }else {
                ledger.create(id)
            }
            created=true
        }
        // Search/selection may have happened before this worker acquired its
        // durable journal slot, but a transfer must never skip A3's state
        // transitions. Failed durable writes remain visible as prior phase.
        val order=listOf(
            A3AcquisitionLedger.Phase.REQUESTED,
            A3AcquisitionLedger.Phase.SEARCHING,
            A3AcquisitionLedger.Phase.AWAITING_SELECTION,
            A3AcquisitionLedger.Phase.SELECTED,
        )
        while(true) {
            val phase=checkNotNull(ledger.get(id)).phase
            val position=order.indexOf(phase)
            if(position<0)break
            val next=if(position==order.lastIndex)
                A3AcquisitionLedger.Phase.TRANSFERRING else order[position+1]
            ledger.advance(id,next)
        }
        check(ledger.get(id)?.phase==A3AcquisitionLedger.Phase.TRANSFERRING) {
            "A3 auto journal cannot restart completed or uncertain work"
        }
    }

    @Synchronized
    fun staged() {
        check(created)
        ledger.advance(id,A3AcquisitionLedger.Phase.STAGED)
    }

    @Synchronized fun markUncertain() { uncertain=true }

    @Synchronized fun markRolledBack() { rolledBack=true }

    /**
     * Never release a PUBLISHING job after an uncertain provider operation:
     * the URI may be visible even when A3.available could not commit.
     * Manual reconciliation must explicitly resolve it first.
     */
    @Synchronized
    fun finish() {
        if(!created)return
        val phase=ledger.get(id)?.phase ?: return
        if(phase in setOf(
                A3AcquisitionLedger.Phase.AVAILABLE,
                A3AcquisitionLedger.Phase.RECONCILE) ||
            phase in setOf(A3AcquisitionLedger.Phase.FAILED,
                A3AcquisitionLedger.Phase.CANCELLED,
                A3AcquisitionLedger.Phase.INTERRUPTED))return
        if(uncertain && !rolledBack)return
        if(phase==A3AcquisitionLedger.Phase.PUBLISHING && !rolledBack)return
        ledger.advance(id,A3AcquisitionLedger.Phase.FAILED)
    }
}
