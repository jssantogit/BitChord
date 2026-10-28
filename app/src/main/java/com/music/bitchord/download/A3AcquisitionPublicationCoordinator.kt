package com.music.bitchord.download

import java.io.IOException

/**
 * Transaction boundary linking a completed A2 publisher with the durable A3
 * journal. The local URI is only claimed after the provider returned success.
 *
 * If the durable journal fails after media becomes visible, attempt to delete
 * that URI. If deletion also fails, surface a distinct rollback error rather
 * than reporting completion or losing the original failure cause.
 */
/**
 * Typed failure information stays IN PROCESS. A durable publication may have
 * succeeded before an A3 journal failure; the caller must not blindly retry.
 * No content URI is included in exception messages or diagnostics.
 */
internal sealed class A3PublicationCommitFailure(
    message:String,cause:Exception,
):IOException(message,cause) {
    /** A visible URI was removed successfully; no phantom local row remains. */
    class RolledBack(cause:Exception):A3PublicationCommitFailure(
        "A3 failed to commit published URI; artifact removed",cause)

    /** Rollback failed. Explicit reconciliation must use the private URI. */
    class ReconciliationRequired(
        val publishedContentUri:String,cause:Exception,
    ):A3PublicationCommitFailure(
        "A3 published artifact rollback failed; reconciliation required",cause) {
        override fun toString()="A3PublicationCommitFailure(reconciliation-required)"
    }
}

internal class A3AcquisitionPublicationCoordinator(
    private val journal:A3AcquisitionLedger,
    private val deletePublishedUri:(String)->Boolean,
) {
    fun publish(
        id:String,
        commitGuard:((()->Unit)->Unit)={ action -> action() },
        publishAlreadyStaged:()->A2LocalAudioPublisher.Published
    ):A2LocalAudioPublisher.Published {
        journal.advance(id,A3AcquisitionLedger.Phase.PUBLISHING)
        val published=publishAlreadyStaged()
        try {
            // The caller may require an account-generation check synchronized
            // against logout immediately before committing AVAILABLE. The
            // output URI is rolled back if authorization was revoked during A2.
            commitGuard { journal.available(id,published.contentUri,published.byteCount) }
        }catch(error:Exception) {
            val removed=runCatching {
                deletePublishedUri(published.contentUri)
            }.getOrDefault(false)
            if(!removed) {
                // Best effort to persist the orphan even when the initial
                // AVAILABLE commit failed. If the journal itself is
                // unavailable, the exception still carries the private URI.
                runCatching {
                    journal.reconcile(id,published.contentUri,published.byteCount)
                }
                throw A3PublicationCommitFailure.ReconciliationRequired(published.contentUri,error)
            }
            throw A3PublicationCommitFailure.RolledBack(error)
        }
        return published
    }
}
