package com.music.bitchord.download

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * A manual A3 writer may finish AFTER the Activity has been destroyed.
 * Revoking sockets/interrupting the worker does not prove that a MediaStore
 * commit has finished. Keep the review lease until the executor terminates,
 * preventing a concurrent automatic transfer or second manual recovery.
 *
 * If an uninterruptible writer never terminates, keep the lease fail-closed.
 * Never block the Android main thread while waiting for the worker.
 */
internal object SoulseekManualRecoveryTermination {
    fun releaseAfterStop(
        worker:ExecutorService,
        release:()->Unit,
    ) {
        worker.shutdownNow()
        if(worker.isTerminated) {
            release()
            return
        }
        Thread({
            try {
                while(!worker.awaitTermination(30,TimeUnit.SECONDS)) {
                    // The lease remains held until every publication exits.
                }
                release()
            }catch(_:InterruptedException) {
                // Unknown writer state: do NOT authorize another transfer.
                Thread.currentThread().interrupt()
            }
        },"SoulseekManualRecoveryCleanup").apply {
            isDaemon=true
            start()
        }
    }
}
