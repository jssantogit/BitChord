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
}
