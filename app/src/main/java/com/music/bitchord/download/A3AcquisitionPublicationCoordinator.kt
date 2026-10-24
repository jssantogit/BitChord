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
internal class A3AcquisitionPublicationCoordinator(
    private val journal:A3AcquisitionLedger,
    private val deletePublishedUri:(String)->Boolean,
) {
    fun publish(
        id:String,
        publishAlreadyStaged:()->A2LocalAudioPublisher.Published,
        commitGuard:((()->Unit)->Unit)={ action -> action() }
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
                throw IOException("A3 published artifact rollback failed",error)
            }
            throw IOException("A3 failed to commit published URI; artifact removed",error)
        }
        return published
    }
}
