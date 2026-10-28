package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A bounded, per-track policy+I/O boundary for a FUTURE opt-in Soulseek worker.
 *
 * Unlike the shadow-only planners, this object composes a port that MAY
 * perform P/F and A3 publication. No production port is installed or invoked
 * by the Android app yet. The service must supply account-generation guards,
 * cancelable private transport, a real Android decoder and a durable publisher.
 *
 * Network failure may retry only another metadata-equivalent source. Once
 * bytes arrive, any decoding or publication failure abstains: never silently
 * spend another peer's bandwidth or publish unverified bytes.
 */
internal class A0AutoAcquisitionExecutor(private val port:Port) {
    interface Port {
        /** Transport MUST enforce one authenticated Soulseek session and P/F limits. */
        fun fetch(
            candidate:A0ObservedCandidateHandoff.Choice,
            isCancelled:()->Boolean,
        ):FetchResult

        /** In production, call A0AndroidAudioTechnicalProbe on the private receipt. */
        fun inspect(
            receipt:A0VerifiedByteStager.Receipt,
            expectedExtension:String,
            isCancelled:()->Boolean,
        ):A0AutoPostTransferTechnicalGate.Decoded?

        /** Caller MUST use A3/A2 with account-generation commitGuard, not raw file I/O. */
        fun publish(
            receipt:A0VerifiedByteStager.Receipt,
            safeName:String,
            isCancelled:()->Boolean,
        ):Boolean

        /** MUST discard private scratch even on user logout, invalid audio or failure. */
        fun cleanup(receipt:A0VerifiedByteStager.Receipt)
    }

    sealed interface FetchResult {
        data object RetryableConnectionFailure:FetchResult
        class Received(val receipt:A0VerifiedByteStager.Receipt):FetchResult
    }

    enum class Status { PUBLISHED, MANUAL_REVIEW, CANCELLED }
    enum class Reason {
        NO_EXPLICIT_CONSENT, NO_STRONG_CANDIDATE, EXHAUSTED_PEERS,
        TRANSPORT_FAILURE, TECHNICAL_REJECTION,
        INVALID_DESTINATION, PUBLICATION_FAILURE, CLEANUP_FAILURE,
        REVOKED_OR_CANCELLED, SINGLE_FLIGHT
    }
    class Result(
        val status:Status,
        val reason:Reason?,
        val attempts:Int,
    ) {
        override fun toString()="AutoAcquisitionResult(status=$status,reason=$reason,attempts=$attempts)"
    }

    private val running=AtomicBoolean(false)
    private fun manual(reason:Reason,attempts:Int=0)=
        Result(Status.MANUAL_REVIEW,reason,attempts)
    private fun cancelled(attempts:Int=0)=
        Result(Status.CANCELLED,Reason.REVOKED_OR_CANCELLED,attempts)

    /**
     * Must run on an I/O worker, never on the Android UI thread. This is NOT
     * currently reachable via Downloads/DownloadService. All peer/file IDs
     * remain in memory and no diagnostics emit them.
     */
    fun run(
        target:SoulseekBatchPlan.Entry,
        observed:List<A0ObservedCandidateHandoff.Choice>,
        profile:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        userRequested:Boolean,
        optedIn:Boolean,
        isCancelled:()->Boolean={false},
        stillAuthorized:()->Boolean={true},
    ):Result {
        if(!userRequested || !optedIn)return manual(Reason.NO_EXPLICIT_CONSENT)
        if(!running.compareAndSet(false,true))return manual(Reason.SINGLE_FLIGHT)
        try {
            fun revoked()=isCancelled() || !stillAuthorized() ||
                Thread.currentThread().isInterrupted
            if(revoked())return cancelled()
            val choice=A0AutoCandidateSelectionPolicy.preview(target,observed,profile)
            if(choice.status!=A0AutoCandidateSelectionPolicy.Status.PROVISIONAL ||
                choice.sequentialAlternatives.isEmpty())
                return manual(Reason.NO_STRONG_CANDIDATE)
            val order=choice.sequentialAlternatives
            val sequence=A0AutoRetrySequencer(order.size,
                optIn=true,equivalentClaimsConfirmed=true)
            while(true) {
                if(revoked()) {
                    sequence.cancel()
                    return cancelled(sequence.attemptsStarted)
                }
                val attempt=sequence.begin()
                    ?: return manual(Reason.EXHAUSTED_PEERS,sequence.attemptsStarted)
                val candidate=order[attempt.alternativeIndex]
                val download=try {
                    port.fetch(candidate,::revoked)
                }catch(_:Exception) {
                    sequence.cancel()
                    return if(revoked())cancelled(sequence.attemptsStarted)
                        else manual(Reason.TRANSPORT_FAILURE,sequence.attemptsStarted)
                }
                when(download) {
                    FetchResult.RetryableConnectionFailure -> {
                        sequence.complete(attempt,false)
                        continue
                    }
                    is FetchResult.Received -> {
                        var result:Result
                        val receipt=download.receipt
                        try {
                            if(revoked()) {
                                sequence.cancel()
                                result=cancelled(sequence.attemptsStarted)
                            } else {
                                val ext=candidate.claim.format.lowercase(
                                    java.util.Locale.ROOT)
                                val decoded=try {
                                    port.inspect(receipt,ext,::revoked)
                                }catch(_:Exception) { null }
                                val assessed=A0AutoPostTransferTechnicalGate.assess(
                                    profile=profile,requestedExtension=ext,
                                    expectedDurationSeconds=target.durationSeconds,
                                    receiptByteCount=receipt.byteCount,
                                    actualByteCount=receipt.file.length(),
                                    decoded=decoded)
                                if(revoked()) {
                                    sequence.cancel()
                                    result=cancelled(sequence.attemptsStarted)
                                } else if(assessed.status!=
                                    A0AutoPostTransferTechnicalGate.Status.TECHNICALLY_PLAUSIBLE) {
                                    sequence.cancel()
                                    result=manual(Reason.TECHNICAL_REJECTION,
                                        sequence.attemptsStarted)
                                } else {
                                    val name=A5ManualSaveGate.safeDestinationName(
                                        candidate.displayName,receipt.byteCount)
                                    if(name==null) {
                                        sequence.cancel()
                                        result=manual(Reason.INVALID_DESTINATION,
                                            sequence.attemptsStarted)
                                    } else {
                                        val published=try {
                                            // The real Port MUST check account generation
                                            // synchronously within A3's AVAILABLE commit.
                                            port.publish(receipt,name,::revoked)
                                        }catch(_:Exception) { false }
                                        sequence.complete(attempt,published)
                                        result=if(published)
                                            Result(Status.PUBLISHED,null,sequence.attemptsStarted)
                                        else if(revoked())cancelled(sequence.attemptsStarted)
                                        else manual(Reason.PUBLICATION_FAILURE,
                                            sequence.attemptsStarted)
                                    }
                                }
                            }
                        }catch(_:Exception) {
                            sequence.cancel()
                            result=if(revoked())cancelled(sequence.attemptsStarted)
                                else manual(Reason.TECHNICAL_REJECTION,sequence.attemptsStarted)
                        } finally {
                            // Always release a received private receipt. A
                            // failed cleanup cannot silently become success.
                            try {
                                port.cleanup(receipt)
                            }catch(_:Exception) {
                                // No retry on cleanup errors, which may occur
                                // AFTER a durable AVAILABLE commit. Never
                                // request the same track again automatically.
                                sequence.cancel()
                                return Result(Status.MANUAL_REVIEW,Reason.CLEANUP_FAILURE,
                                    sequence.attemptsStarted)
                            }
                        }
                        return result
                    }
                }
            }
        }finally{
            running.set(false)
        }
    }
}
