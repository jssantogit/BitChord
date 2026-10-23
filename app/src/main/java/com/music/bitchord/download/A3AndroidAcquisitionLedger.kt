package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * A3 store for future Soulseek requests only. SharedPreferences.commit()
 * provides synchronous persistence. The store has its own preferences file
 * so no existing downloads or local songs settings can be overwritten.
 *
 * Open once per app process; recover stale in-flight states exactly once. No
 * transfer, resumption, UI notification, or library import is launched here.
 */
internal object A3AndroidAcquisitionLedger {
    @Volatile private var active:A3AcquisitionLedger?=null

    /**
     * Future user-requested download worker entry point. Caller must have
     * already completed bounded P/F into a private file, recorded STAGED in
     * this ledger and established the user's right to save that file.
     * This does not run from A0's experimental screen.
     */
    fun publishStaged(
        context:Context,
        jobId:String,
        privateFile:File,
        safeName:String,
        byteCount:Long,
        observedDigest:String,
        isCancelled:()->Boolean={false}
    ):A2LocalAudioPublisher.Published {
        val app=context.applicationContext
        val coordinator=A3AcquisitionPublicationCoordinator(get(app)) { uri ->
            runCatching {
                app.contentResolver.delete(Uri.parse(uri),null,null)>0
            }.getOrDefault(false)
        }
        return coordinator.publish(jobId) {
            A2AndroidAudioDestination.publishPrivateReceipt(
                app,privateFile,safeName,byteCount,observedDigest,isCancelled
            )
        }
    }

    fun get(context:Context):A3AcquisitionLedger {
        active?.let { return it }
        return synchronized(this) {
            active ?: run {
                val prefs=context.applicationContext.getSharedPreferences(
                    "bitchord_acquisition_jobs_v1",Context.MODE_PRIVATE
                )
                val ledger=A3AcquisitionLedger(
                    object:A3AcquisitionLedger.Store {
                        override fun read():String?=prefs.getString("jobs",null)
                        override fun commit(raw:String):Boolean =
                            prefs.edit().putString("jobs",raw).commit()
                    }
                )
                ledger.interruptActiveOnColdStart()
                ledger.also { active=it }
            }
        }
    }
}
