package com.music.bitchord.acquisition.a0

import android.content.Context
import android.os.Looper
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.A1AndroidDownloadDestination
import com.music.bitchord.download.A1DownloadDestinationPolicy
import com.music.bitchord.download.A3AndroidAcquisitionLedger
import com.music.bitchord.download.A3AcquisitionLedger
import com.music.bitchord.download.A3AutomaticReservationScope
import java.util.UUID
import java.io.IOException
import com.music.bitchord.download.SoulseekBatchPlan

/**
 * App-private opt-in, independent of format/quality preferences. Defaults OFF
 * on first launch and after missing/corrupted settings. The devDebug Settings
 * control is its only explicit writer; disabling it revokes the active session.
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
 * Invoked only from the explicitly requested devDebug Downloads foreground
 * queue, off-main on Dispatchers.IO. No automatic launch at startup, no
 * unattended background scanning and no production-flavor P/F transport.
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
        onSlotResolved:(Int,A0AutoBatchExecutor.TrackStatus)->Unit={ _,_ -> },
        /** Only invoked AFTER A3 durably proves zero P/F for the whole album. */
        onUnusedReservationReleased:()->Unit={},
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
        // Do not permanently reserve a recording just because an account
        // was never configured. This read opens NO socket and the secret is
        // wiped immediately; the authenticated transport reads it freshly.
        val account=runCatching {
            SoulseekCredentialVault(app).read()
        }.getOrNull() ?: return refused(entries.size)
        try {
            if(account.username.isBlank())return refused(entries.size)
        }finally {
            account.erase()
        }
        if(stopped())return refused(entries.size)
        // There must not be another auto P/F worker inside this private cache:
        // Downloads.autoMutex serializes the entire run. Clear crash leftovers
        // BEFORE irreversible A3 reservation so cleanup failure does not
        // poison the canonical identity of an otherwise unattempted track.
        A0AutoScratchRecovery.clean(app.cacheDir)
        if(stopped())return refused(entries.size)

        // Atomic on the A3 journal BEFORE search, login or peer discovery.
        // Reboot does not erase this reservation: a duplicate recording ID
        // or same artist/title+release fingerprint fails closed.
        val jobs=entries.associate { it.trackId to UUID.randomUUID().toString() }
        A3AndroidAcquisitionLedger.get(app).reserveAutomaticBatch(
            entries.map { entry ->
                A3AcquisitionLedger.AutomaticSlot(
                    id=checkNotNull(jobs[entry.trackId]),
                    recordingId=entry.trackId,
                    query=entry.query,
                    album=entry.album,
                )
            },
            commitGuard={ commit ->
                // Atomic with Soulseek account generation revocation:
                // a stale worker cannot permanently claim a recording while
                // another thread is logging out or replacing credentials.
                SoulseekActiveSession.commitIfCurrent(generation) {
                    if(stopped())throw IOException("Auto reservation revoked")
                    commit()
                }
            },
        )
        // Cover cancellation exactly after reservation as well as failure
        // constructing/closing discovery. A3 only releases if all slots are
        // still REQUESTED; no transfer, media URI or receipt can be erased.
        val outcome=A3AutomaticReservationScope(
            A3AndroidAcquisitionLedger.get(app)
        ).run(
            jobs.values.toList(),
            onDurablyReleased=onUnusedReservationReleased,
        ) {
            if(stopped())refused(entries.size)
            else {
                val discovery=A0FreshAutoSearchTransport(app,generation,quality)
                val executor=A0AutoBatchExecutor { entry ->
                    A0AndroidAutoAcquisitionPort(
                        app,generation,
                        A0FreshAuthenticatedAutoTransport(app,generation),
                        jobId=checkNotNull(jobs[entry.trackId]),
                        reservedAutomatic=true,
                    )
                }
                A0AutoDiscoveryCoordinator(discovery,executor).run(
                    entries,quality,userRequested=true,optedIn=true,
                    isCancelled=::stopped,
                    stillAuthorized={
                        SoulseekActiveSession.isCurrent(generation) &&
                            A0AndroidAutoConsent.enabled(app)
                    },
                    onSlotResolved=onSlotResolved)
            }
        }
        return A0AutoDiscoveryCoordinator.Report(
            outcome.value.searches,outcome.value.batch,
            unusedReservationReleased=outcome.unusedReservationReleased)
    }
}
