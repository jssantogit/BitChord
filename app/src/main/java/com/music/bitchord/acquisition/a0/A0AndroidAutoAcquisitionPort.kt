package com.music.bitchord.acquisition.a0

import android.content.Context
import android.os.Looper
import com.music.bitchord.download.A3AndroidAcquisitionLedger
import com.music.bitchord.download.A3PublicationCommitFailure
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real Android receipt validation + transactional publication half of #184.
 * This is NOT registered with Downloads/DownloadService. The injected
 * AuthenticatedTransport must create a fresh bounded Soulseek P/F connection
 * per candidate, never reuse a reader-owned search socket, and close on cancel.
 *
 * Unlike a fake publication port, this uses actual Android MediaExtractor,
 * private scratch storage, A2 publication and the durable A3 journal.
 */
internal class A0AndroidAutoAcquisitionPort(
    context:Context,
    private val expectedGeneration:Long,
    private val transport:AuthenticatedTransport,
    private val jobId:String=UUID.randomUUID().toString(),
    private val reservedAutomatic:Boolean=false,
):A0AutoAcquisitionExecutor.Port {
    interface AuthenticatedTransport {
        fun receive(
            candidate:A0ObservedCandidateHandoff.Choice,
            scratchDirectory:File,
            isCancelled:()->Boolean,
        ):A0AutoAcquisitionExecutor.FetchResult
        fun cancel()
    }

    private val app=context.applicationContext
    private val job by lazy {
        A0AutoJobJournal(A3AndroidAcquisitionLedger.get(app),jobId,
            reservedAutomatic=reservedAutomatic)
    }
    private val closed=AtomicBoolean(false)
    private val scratchRoot by lazy {
        val cache=app.cacheDir.canonicalFile
        val dir=File(cache,"soulseek-auto-receipts")
        if(Files.isSymbolicLink(dir.toPath()) ||
            (!dir.isDirectory && !dir.mkdirs()) ||
            dir.canonicalFile.parentFile!=cache)
            throw IOException("Soulseek private receipt directory unavailable")
        dir.canonicalFile
    }

    private fun requireWorker() {
        check(Looper.myLooper()!=Looper.getMainLooper()) {
            "Soulseek auto job cannot run on Android main thread"
        }
    }
    private fun revoked(cancel:()->Boolean):Boolean =
        closed.get() || cancel() ||
            !SoulseekActiveSession.isCurrent(expectedGeneration) ||
            Thread.currentThread().isInterrupted

    private fun privateReceipt(receipt:A0VerifiedByteStager.Receipt):Boolean =
        runCatching {
            val file=receipt.file
            file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                file.canonicalFile.parentFile==scratchRoot &&
                file.length()==receipt.byteCount &&
                receipt.byteCount in A0ObservedTransferLimits.MIN_AUDIO_BYTES..
                    A0ObservedTransferLimits.MAX_AUDIO_BYTES
        }.getOrDefault(false)

    override fun fetch(
        candidate:A0ObservedCandidateHandoff.Choice,
        isCancelled:()->Boolean,
    ):A0AutoAcquisitionExecutor.FetchResult {
        requireWorker()
        if(revoked(isCancelled))throw IOException("Soulseek auto session revoked")
        job.beginTransfer()
        val result=transport.receive(candidate,scratchRoot) { revoked(isCancelled) }
        if(result is A0AutoAcquisitionExecutor.FetchResult.Received) {
            val receipt=result.receipt
            if(!privateReceipt(receipt) || revoked(isCancelled)) {
                runCatching {receipt.file.delete()}
                throw IOException("Soulseek private receipt rejected")
            }
            try {
                job.staged()
            }catch(error:Exception) {
                runCatching {receipt.file.delete()}
                throw IOException("Soulseek durable staging failed",error)
            }
        }
        return result
    }

    override fun inspect(
        receipt:A0VerifiedByteStager.Receipt,
        expectedExtension:String,
        isCancelled:()->Boolean,
    ):A0AutoPostTransferTechnicalGate.Decoded? {
        requireWorker()
        if(revoked(isCancelled) || !privateReceipt(receipt))return null
        return A0AndroidAudioTechnicalProbe.inspect(
            receipt.file,expectedExtension) { revoked(isCancelled) }
    }

    override fun publish(
        receipt:A0VerifiedByteStager.Receipt,
        safeName:String,
        isCancelled:()->Boolean,
    ):A0AutoAcquisitionExecutor.PublishResult {
        requireWorker()
        if(revoked(isCancelled) || !privateReceipt(receipt))
            return A0AutoAcquisitionExecutor.PublishResult.ROLLED_BACK
        return try {
            A3AndroidAcquisitionLedger.publishStaged(
                context=app,jobId=jobId,
                privateFile=receipt.file,safeName=safeName,
                byteCount=receipt.byteCount,observedDigest=receipt.sha256Hex,
                isCancelled={revoked(isCancelled)},
                commitGuard={ commit ->
                    SoulseekActiveSession.commitIfCurrent(expectedGeneration,commit)
                }
            )
            A0AutoAcquisitionExecutor.PublishResult.DURABLE
        }catch(_:A3PublicationCommitFailure.RolledBack) {
            job.markRolledBack()
            A0AutoAcquisitionExecutor.PublishResult.ROLLED_BACK
        }catch(_:A3PublicationCommitFailure.ReconciliationRequired) {
            job.markUncertain()
            A0AutoAcquisitionExecutor.PublishResult.UNCERTAIN
        }catch(_:Exception) {
            // A3 may have made a provider row visible before failure;
            // without confirmed rollback, never permit silent re-download.
            job.markUncertain()
            A0AutoAcquisitionExecutor.PublishResult.UNCERTAIN
        }
    }

    override fun cleanup(receipt:A0VerifiedByteStager.Receipt) {
        requireWorker()
        if(!privateReceipt(receipt) || !receipt.file.delete())
            throw IOException("Soulseek private receipt cleanup failed")
    }

    override fun finish() {
        if(!closed.compareAndSet(false,true))return
        try {transport.cancel()}finally{job.finish()}
    }
}
