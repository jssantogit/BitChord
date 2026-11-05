package com.music.bitchord.data.scrobbling

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A durable write must finish before scheduling network work. WorkManager owns
 * delivery across process death and offline periods. The worker never stores
 * credentials, reads them from the current account settings at execution time.
 */
internal object ListenBrainzOutbox {
    private const val WORK_NAME = "listenbrainz-qualified-listens"
    private const val STORE_FILE = "listenbrainz-pending-v1.json"

    fun capture(
        token: String,
        occurrenceId: String,
        song: com.music.bitchord.data.model.Song,
        startMs: Long,
        endMs: Long,
        listenedMs: Long,
        durationMs: Long?,
        primaryArtistOnly: Boolean,
    ) = PendingListenBrainzListen(
        occurrenceId = occurrenceId,
        accountKey = accountKey(token),
        startMs = startMs,
        endMs = endMs,
        listenedMs = listenedMs,
        durationMs = durationMs?.takeIf { it > 0L } ?: 0L,
        artist = if (primaryArtistOnly) song.artist.primaryArtist() else song.artist,
        title = song.title,
        album = song.albumName.orEmpty(),
    )

    fun store(context: Context): ListenBrainzOutboxStore =
        ListenBrainzOutboxStore(File(context.applicationContext.noBackupFilesDir, STORE_FILE))

    fun accountKey(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun enqueue(context: Context, entry: PendingListenBrainzListen): Boolean {
        val added = store(context).enqueue(entry)
        if (added) schedule(context)
        return added
    }

    /** Reconcile persisted listens after an app/process restart. */
    fun recover(context: Context) {
        if (!AppSettings.scrobblingAvailable || !AppSettings.listenBrainzEnabled.value) return
        val token = AppSettings.listenBrainzToken.value
        if (token.isBlank()) return
        if (store(context).pending(accountKey(token)).isNotEmpty()) schedule(context)
    }

    /** Disabling or changing accounts revokes consent to replay past listens. */
    fun forget(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        store(context).clear()
    }

    private fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ListenBrainzOutboxWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE avoids the race where KEEP ignores a listen added
        // just as a currently running worker is finishing its drain loop.
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request,
        )
    }
}

/**
 * At-least-once transport with exactly-once *local* occurrence admission.
 * The remote API has no confirmed idempotency key: a connection failure after
 * remote acceptance can produce a duplicate on retry. Never claim exactly-once
 * server delivery.
 */
internal class ListenBrainzOutboxWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!AppSettings.scrobblingAvailable || !AppSettings.listenBrainzEnabled.value) {
            return Result.success()
        }
        val token = AppSettings.listenBrainzToken.value
        if (token.isBlank()) return Result.success()

        val accountKey = ListenBrainzOutbox.accountKey(token)
        val store = ListenBrainzOutbox.store(applicationContext)
        try {
            store.keepOnlyAccount(accountKey)
            repeat(32) {
                if (!AppSettings.listenBrainzEnabled.value ||
                    AppSettings.listenBrainzToken.value != token
                ) return Result.success()

                val next = store.pending(accountKey).firstOrNull() ?: return Result.success()
                when (ListenBrainzManager.submitPending(token, next)) {
                    ListenBrainzSubmissionResult.ACCEPTED -> {
                        store.remove(next.occurrenceId, accountKey)
                    }
                    ListenBrainzSubmissionResult.RETRYABLE -> return Result.retry()
                    // Keep data when credentials are temporarily invalid; resume
                    // after explicit reconfiguration rather than hammering 401.
                    ListenBrainzSubmissionResult.AUTH_ERROR -> return Result.success()
                    // Malformed metadata will never become valid by retrying.
                    ListenBrainzSubmissionResult.REJECTED -> {
                        Log.w("ListenBrainzOutbox", "Discarding a rejected listen occurrence")
                        store.remove(next.occurrenceId, accountKey)
                    }
                }
            }
            return if (store.pending(accountKey).isEmpty()) Result.success() else Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("ListenBrainzOutbox", "Outbox drain failed; retaining pending listens")
            return Result.retry()
        }
    }
}
