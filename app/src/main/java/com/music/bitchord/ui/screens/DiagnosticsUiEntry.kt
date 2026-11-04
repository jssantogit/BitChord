package com.music.bitchord.ui.screens

import android.content.Context
import android.content.Intent

internal object DiagnosticsUiEntry {
    private const val DIAGNOSTICS_ACTIVITY =
        "com.music.bitchord.diagnostics.DiagnosticsActivity"

    fun isAvailable(context: Context): Boolean =
        Intent()
            .setClassName(context.packageName, DIAGNOSTICS_ACTIVITY)
            .resolveActivity(context.packageManager) != null

    fun open(context: Context) {
        context.startActivity(
            Intent().setClassName(context.packageName, DIAGNOSTICS_ACTIVITY),
        )
    }
    /** Export remains dev-only; navigation/display stay inside the host app. */
    fun openShare(context: Context) {
        if (!isAvailable(context)) return
        context.startActivity(
            Intent().setClassName(context.packageName, DIAGNOSTICS_ACTIVITY)
                .putExtra("share_only", true),
        )
    }

}
