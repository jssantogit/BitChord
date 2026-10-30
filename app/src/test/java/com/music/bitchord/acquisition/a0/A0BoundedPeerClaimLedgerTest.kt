package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0BoundedPeerClaimLedgerTest {
    @Test fun pressureEvictsOnlyOldestEntryNotEveryPriorIdentity() {
        val seen=A0BoundedPeerClaimLedger(8)
        repeat(8){assertTrue(seen.admit("peer","file-$it"))}
        assertFalse(seen.admit("peer","file-7"))
        assertTrue(seen.admit("peer","file-8"))
        assertEquals(8,seen.retainedCount)
        assertFalse("Recent identity must survive capacity pressure",
            seen.admit("peer","file-7"))
        assertTrue("Only the oldest evicted key may return",
            seen.admit("peer","file-0"))
        assertEquals(8,seen.retainedCount)
    }

    @Test fun thousandsOfUntrustedFilesKeepRecentDuplicateProtection() {
        val seen=A0BoundedPeerClaimLedger()
        repeat(9000){assertTrue(seen.admit("peer-$it","remote/$it.flac"))}
        assertEquals(8192,seen.retainedCount)
        assertFalse(seen.admit("peer-8999","remote/8999.flac"))
        assertFalse(seen.admit("peer-8998","remote/8998.flac"))
        assertEquals(8192,seen.retainedCount)
        assertTrue(seen.admit("peer-0","remote/0.flac"))
        assertEquals(8192,seen.retainedCount)
        assertFalse(seen.toString().contains("remote"))
        assertFalse(seen.toString().contains("peer-"))
    }

    @Test fun samePathFromDifferentPeersIsNotConflated() {
        val seen=A0BoundedPeerClaimLedger(8)
        assertTrue(seen.admit("peer-a","a.flac"))
        assertTrue(seen.admit("peer-b","a.flac"))
        assertFalse(seen.admit("peer-a","a.flac"))
        assertEquals(2,seen.retainedCount)
    }
}
