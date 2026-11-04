package com.music.bitchord.data

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.sources.SourceResolver

/**
 * Android runtime hooks retained for the shared diagnostics clock and log
 * output. Retired InnerTubeX player configuration and BotGuard initialization
 * are deliberately absent.
 */
object AndroidDiagnosticsHooks {

    /** Before anything logs or resolves. */
    fun installEarly() {
        MonotonicClock.nowMs = SystemClock::elapsedRealtime
        TrackLog.echo = { level, tag, message, error ->
            if (BuildConfig.DEBUG) {
                when (level) {
                    'D' -> Log.d(tag, message, error)
                    'I' -> Log.i(tag, message, error)
                    'W' -> Log.w(tag, message, error)
                    else -> Log.e(tag, message, error)
                }
            }
        }
        TrackLog.sourcesLine = {
            "sources: request=${SourceResolver.requestForNow()}"
        }
        TrackLog.headerLines = {
            listOf(
                "build: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            )
        }
    }

}
