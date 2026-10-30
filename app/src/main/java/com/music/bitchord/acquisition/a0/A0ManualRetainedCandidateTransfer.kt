package com.music.bitchord.acquisition.a0

import android.content.Context
import android.os.Looper
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.A3AcquisitionLedger
import com.music.bitchord.download.A3AndroidAcquisitionLedger
import com.music.bitchord.download.SoulseekBatchPlan
import java.io.IOException
import java.util.UUID

/**
 * A HUMAN selected one of the peer offers already found by automatic search.
 * No repeated discovery, no unattended selection and no second P/F retry.
 * The candidate is an ephemeral in-process handle, not an Intent extra.
 *
 * Reuses a REQUESTED A3 automatic identity if a sibling transfer left the
 * album reservation intact; otherwise synchronously reserves that canonical
 * identity before the first socket opens. NEVER resurrect a failed transfer.
 */
internal object A0ManualRetainedCandidateTransfer {
    enum class Status {
        PUBLISHED, PRECHECK_REJECTED, TECHNICAL_REJECTION,
        TRANSFER_FAILED, VERIFY_LIBRARY,
    }
    enum class Stage {
        PRECHECK_REJECTED, SESSION_REVOKED, A3_RESERVATION_BLOCKED,
        FETCH_STARTED, FETCH_FAILED, RECEIPT_STAGED, PROBE_UNAVAILABLE,
        PROBE_COMPLETE, TECHNICAL_GATE_REJECTED, DESTINATION_INVALID,
        PUBLICATION_DURABLE, PUBLICATION_ROLLED_BACK,
        PUBLICATION_UNCERTAIN, UNEXPECTED_ERROR,
    }

    fun run(
        context:Context,
        entry:SoulseekBatchPlan.Entry,
        choice:A0ObservedCandidateHandoff.Choice,
        profile:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        sourceSong:Song,
        isCancelled:()->Boolean,
        onTransport:(A0FreshAuthenticatedAutoTransport)->Unit={},
        onDiagnostic:(Stage,A0AutoPostTransferTechnicalGate.Reason?)->Unit={_,_->},
    ):Status {
        check(BuildConfig.DEBUG && BuildConfig.FLAVOR=="dev")
        check(Looper.myLooper()!=Looper.getMainLooper())
        fun report(stage:Stage,reason:A0AutoPostTransferTechnicalGate.Reason?=null) {
            try { onDiagnostic(stage,reason) }catch(_:Exception) {}
        }
        val allowed=A0ManualReviewCandidatePolicy.retain(
            entry,listOf(choice),profile).singleOrNull()
        if(allowed!==choice) {
            report(Stage.PRECHECK_REJECTED)
            return Status.PRECHECK_REJECTED
        }
        if(isCancelled()) {
            report(Stage.SESSION_REVOKED)
            return Status.TRANSFER_FAILED
        }
        val app=context.applicationContext
        val generation=SoulseekActiveSession.generation()
        fun revoked()=isCancelled() ||
            !SoulseekActiveSession.isCurrent(generation) ||
            Thread.currentThread().isInterrupted
        if(revoked()) {
            report(Stage.SESSION_REVOKED)
            return Status.TRANSFER_FAILED
        }
        val ledger=A3AndroidAcquisitionLedger.get(app)
        val id=try {
            val existing=ledger.reservedForExplicitManualReview(
                entry.trackId,entry.query,entry.album)
            when {
            existing==null -> UUID.randomUUID().toString().also { newId ->
                ledger.reserveAutomaticBatch(listOf(
                    A3AcquisitionLedger.AutomaticSlot(
                        newId,entry.trackId,entry.query,entry.album)),
                    commitGuard={ commit ->
                        SoulseekActiveSession.commitIfCurrent(generation) {
                            if(revoked())throw IOException("Review session revoked")
                            commit()
                        }
                    })
            }
            existing.phase==A3AcquisitionLedger.Phase.REQUESTED -> existing.id
                else -> throw A3AcquisitionLedger.AutomaticIdentityAlreadyReserved()
            }
        }catch(_:Exception) {
            report(Stage.A3_RESERVATION_BLOCKED)
            return Status.VERIFY_LIBRARY
        }
        val transport=A0FreshAuthenticatedAutoTransport(app,generation)
        val port=A0AndroidAutoAcquisitionPort(
            app,generation,transport,id,reservedAutomatic=true,
            sourceSong=sourceSong)
        var receipt:A0VerifiedByteStager.Receipt?=null
        var result=Status.TRANSFER_FAILED
        try {
            onTransport(transport)
            if(revoked()) {
                report(Stage.SESSION_REVOKED)
                return Status.TRANSFER_FAILED
            }
            report(Stage.FETCH_STARTED)
            val fetch=port.fetch(choice,::revoked)
            if(fetch !is A0AutoAcquisitionExecutor.FetchResult.Received) {
                report(Stage.FETCH_FAILED)
                return Status.TRANSFER_FAILED
            }
            receipt=fetch.receipt
            report(Stage.RECEIPT_STAGED)
            val extension=choice.claim.format
            val decoded=port.inspect(checkNotNull(receipt),extension,::revoked)
            report(if(decoded==null)Stage.PROBE_UNAVAILABLE else Stage.PROBE_COMPLETE)
            val assessed=A0AutoPostTransferTechnicalGate.assess(
                profile,extension,entry.durationSeconds,
                checkNotNull(receipt).byteCount,
                checkNotNull(receipt).file.length(),decoded)
            if(revoked()) {
                report(Stage.SESSION_REVOKED)
                return Status.TRANSFER_FAILED
            }
            if(assessed.status!=
                A0AutoPostTransferTechnicalGate.Status.TECHNICALLY_PLAUSIBLE) {
                if(decoded!=null)report(Stage.TECHNICAL_GATE_REJECTED,assessed.reason)
                return Status.TECHNICAL_REJECTION
            }
            val safeName=A5ManualSaveGate.safeDestinationName(
                choice.displayName,checkNotNull(receipt).byteCount)
                ?: run {
                    report(Stage.DESTINATION_INVALID)
                    return Status.TECHNICAL_REJECTION
                }
            result=when(port.publish(checkNotNull(receipt),safeName,::revoked)) {
                A0AutoAcquisitionExecutor.PublishResult.DURABLE->Status.PUBLISHED
                A0AutoAcquisitionExecutor.PublishResult.ROLLED_BACK->Status.TRANSFER_FAILED
                A0AutoAcquisitionExecutor.PublishResult.UNCERTAIN->Status.VERIFY_LIBRARY
            }
            report(when(result) {
                Status.PUBLISHED -> Stage.PUBLICATION_DURABLE
                Status.TRANSFER_FAILED -> Stage.PUBLICATION_ROLLED_BACK
                else -> Stage.PUBLICATION_UNCERTAIN
            })
            return result
        }catch(_:Exception) {
            report(Stage.UNEXPECTED_ERROR)
            // Cannot know whether an unexpected exception happened after
            // MediaStore publication. Never grant a second automatic attempt.
            return Status.VERIFY_LIBRARY
        }finally {
            try {
                receipt?.let { port.cleanup(it) }
            }catch(_:Exception) {
                // The A3 identity remains fenced. Cleanup is best-effort
                // only AFTER the physically durable transaction.
            }finally {port.finish()}
        }
    }
}
