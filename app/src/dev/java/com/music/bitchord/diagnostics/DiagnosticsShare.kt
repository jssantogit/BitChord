package com.music.bitchord.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object DiagnosticsShare {
    fun createIntent(context: Context, report: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.diagnostics-files",
            report,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("BitChord diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
