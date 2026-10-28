package com.music.bitchord.acquisition.a0

import android.content.Context
import android.os.Looper
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.A1AndroidDownloadDestination
import com.music.bitchord.download.A1DownloadDestinationPolicy
import com.music.bitchord.download.SoulseekBatchPlan

/**
 * App-private opt-in, independent of format/quality preferences. Defaults OFF
 * on first launch and after missing/corrupted settings. No screen currently
 * calls updateFromUserAction: therefore the new engine is INERT in the app.
 */
internal object A0AndroidAutoConsent {
    private const val PREFS="bitchord_soulseek_auto_v1"
    private const val ENABLED="explicit_unattended_selection"
    fun enabled(context:Context):Boolean = runCatching {
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getBoolean(ENABLED,false)
    }.getOrDefault(false) // Corrupted/incorrect preference type remains OFF.

    /** To be called ONLY by the future explicit settings control. */
    fun updateFromUserAction(context:Context,enabled:Boolean):Boolean = runCatching {
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED,enabled).commit()
    }.getOrDefault(false)
}

/**
 * Full backend wiring: fresh discovery -> bounded source aggregation ->
 * conservative candidate selection -> fresh authenticated P/F ->
 * Android MediaExtractor technical check -> A3/A2 durable publication.
 *
 * Deliberately NOT invoked by DownloadService or any UI. The UI's opt-in
 * switch and queue entrypoint are separate release gates. When a future
 * DownloadService worker calls this, it must do so on Dispatchers.IO and
 * show the existing download notification for the user-requested work.
 */
internal object A0AndroidAutoAcquisitionEngine {
    private fun refused(size:Int):A0AutoDiscoveryCoordinator.Report =
        A0AutoDiscoveryCoordinator.Report(
            0,A0AutoBatchExecutor.Report(
                List(size){A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW},0))

    fun run(
        context:Context,
        entries:List<SoulseekBatchPlan.Entry>,
        quality:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        userRequested:Boolean,
        isCancelled:()->Boolean,
    ):A0AutoDiscoveryCoordinator.Report {
        val app=context.applicationContext
        // Zero socket, vault and journal calls until the user opts in AND
        // this specific request was initiated with a download tap.
        val pilot=BuildConfig.DEBUG && BuildConfig.FLAVOR=="dev"
        val optedIn=A0AndroidAutoConsent.enabled(app)
        if(!userRequested || !optedIn || !pilot ||
            !AppSettings.downloadsAllowedNow)return refused(entries.size)
        check(Looper.myLooper()!=Looper.getMainLooper()) {
            "Automatic Soulseek downloads must run off the UI thread"
        }
        val dest=A1AndroidDownloadDestination.current(app)
        val ready=dest is A1DownloadDestinationPolicy.Resolution.Ready
        if(!A0AutoConsentPolicy.allows(
                userRequested,optedIn,pilot,AppSettings.downloadsAllowedNow,ready))
            return refused(entries.size)
        val generation=SoulseekActiveSession.generation()
        fun stopped()=isCancelled() || !A0AndroidAutoConsent.enabled(app) ||
            !AppSettings.downloadsAllowedNow ||
            !SoulseekActiveSession.isCurrent(generation) ||
            Thread.currentThread().isInterrupted
        if(stopped())return refused(entries.size)
        val discovery=A0FreshAutoSearchTransport(app,generation,quality)
        val executor=A0AutoBatchExecutor {
            A0AndroidAutoAcquisitionPort(
                app,generation,A0FreshAuthenticatedAutoTransport(app,generation)
            )
        }
        return A0AutoDiscoveryCoordinator(discovery,executor).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled=::stopped,
            stillAuthorized={SoulseekActiveSession.isCurrent(generation) &&
                A0AndroidAutoConsent.enabled(app)})
    }
}
