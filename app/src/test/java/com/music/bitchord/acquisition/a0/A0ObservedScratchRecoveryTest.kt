package com.music.bitchord.acquisition.a0

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class A0ObservedScratchRecoveryTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun clearsAbandonedObservedAndPartialFilesButPreservesOtherFiles() {
        val privateDir=temp.newFolder()
        val leftoverObserved=File(privateDir,"a0-11111.observed").apply{writeBytes(byteArrayOf(4,5))}
        val leftoverPartial=File(privateDir,"a0-22222.partial").apply{writeBytes(byteArrayOf(8))}
        val unrelated=File(privateDir,"other.txt").apply{writeText("keep")}
        val strictVerified=File(privateDir,"a0-33333.verified").apply{writeBytes(byteArrayOf(2))}
        val removed=A0ObservedScratchRecovery.clean(privateDir)
        assertEquals(2,removed)
        assertFalse(leftoverObserved.exists())
        assertFalse(leftoverPartial.exists())
        assertTrue(unrelated.exists())
        assertTrue(strictVerified.exists())
    }

    @Test fun rejectsNonDirectoryWithoutDeletingAnything() {
        val file=temp.newFile()
        try{A0ObservedScratchRecovery.clean(file);fail("not a directory")}
        catch (_:IllegalArgumentException){}
    }
}
