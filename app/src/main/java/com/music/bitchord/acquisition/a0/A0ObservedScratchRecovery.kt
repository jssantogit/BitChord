package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.IOException

/**
 * Crash recovery for the DEV DEBUG one-file transport probe only.
 * Only internal random A0 scratch files with .partial or .observed suffix
 * are removed. Never delete .verified artifacts or unrelated private files.
 */
internal object A0ObservedScratchRecovery {
    private val scratchName=Regex("^a0-[A-Za-z0-9_-]+\\.(?:partial|observed)$")

    fun clean(privateDirectory:File):Int {
        require(privateDirectory.isDirectory) { "A0 private scratch directory is missing" }
        var deleted=0
        for(file in privateDirectory.listFiles()
            ?: throw IOException("A0 private scratch cannot be enumerated")) {
            if(!scratchName.matches(file.name) || !file.isFile) continue
            if(!file.delete()) throw IOException("A0 abandoned scratch cleanup failed")
            deleted++
        }
        return deleted
    }
}
