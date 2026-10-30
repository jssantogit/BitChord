package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class A0AutoScratchRecoveryTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun cleanAbandonedPrivateReceiptsBeforeAcquiringAnyA3Slot() {
        val cache=temp.newFolder("cache")
        val scratch=A0AutoScratchRecovery.privateDirectory(cache)
        assertEquals(cache.canonicalFile,scratch.parentFile)
        val incomplete=File(scratch,"a0-19.partial").apply { writeText("partial") }
        val observed=File(scratch,"a0-20.observed").apply { writeText("received") }
        val verified=File(scratch,"a0-21.verified").apply { writeText("keep") }
        val unrelated=File(scratch,"user.txt").apply { writeText("keep") }
        assertEquals(2,A0AutoScratchRecovery.clean(cache))
        assertFalse(incomplete.exists())
        assertFalse(observed.exists())
        assertTrue(verified.exists())
        assertTrue(unrelated.exists())
        assertEquals(0,A0AutoScratchRecovery.clean(cache))
    }

    @Test fun refuseSymlinkedReceiptDirectoryWithoutTouchingExternalFiles() {
        val cache=temp.newFolder("cache-symlink")
        val outside=temp.newFolder("external")
        val sentinel=File(outside,"a0-22.observed").apply { writeText("private data") }
        Files.createSymbolicLink(File(cache,"soulseek-auto-receipts").toPath(),
            outside.toPath())
        try {
            A0AutoScratchRecovery.clean(cache)
            fail("must never follow a scratch directory symlink")
        }catch(_:IOException) {}
        assertTrue(sentinel.exists())
    }

    @Test fun nonDirectoryPathFailsWithoutDeletingAnyFile() {
        val cache=temp.newFolder("cache-file")
        val target=File(cache,"soulseek-auto-receipts").apply {
            writeText("not a directory")
        }
        try {
            A0AutoScratchRecovery.clean(cache)
            fail("unexpected directory type")
        }catch(_:IOException) {}
        assertEquals("not a directory",target.readText())
    }
}
