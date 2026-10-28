package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Crash recovery of only our own Soulseek automatic P/F receipts.
 *
 * Called while Downloads.autoMutex excludes another automatic P/F worker,
 * before reserving the next album's durable A3 identity. On process restart
 * no old sockets or receipts are resurrected. An unknown file, a .verified
 * artifact or a directory outside the app cache is never deleted.
 */
internal object A0AutoScratchRecovery {
    fun privateDirectory(cacheDirectory:File):File {
        val cache=cacheDirectory.canonicalFile
        if(!cache.isDirectory)throw IOException("App private cache unavailable")
        val directory=File(cache,"soulseek-auto-receipts")
        if(Files.isSymbolicLink(directory.toPath()) ||
            (!directory.isDirectory && !directory.mkdirs()) ||
            directory.canonicalFile.parentFile!=cache) {
            throw IOException("Soulseek private receipt directory unavailable")
        }
        return directory.canonicalFile
    }

    /** Deletes abandoned a0-*.partial/.observed only; fail closed on error. */
    fun clean(cacheDirectory:File):Int =
        A0ObservedScratchRecovery.clean(privateDirectory(cacheDirectory))
}
