package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Same-activity repeatability checks without persisted search text or peer identity. */
class A0SearchComparisonTest {
    @Test fun repeatedIdenticalQueryWithZeroPeersIsDistinguishedFromNewQuery() {
        val comparison=A0SearchComparison()
        val first=comparison.observe("ILLIT Magnetic","SUPER REAL ME","HIGH_QUALITY",203,5)
        assertEquals(A0SearchComparison.Kind.FIRST,first.kind)
        val second=comparison.observe("ILLIT  Magnetic","SUPER REAL ME","HIGH_QUALITY",0,0)
        assertEquals(A0SearchComparison.Kind.REPEAT_LOST,second.kind)
        assertEquals(203,second.previousCandidates)
        assertEquals(0,second.currentCandidates)
        assertFalse(second.toString().contains("ILLIT"))
    }
    @Test fun changedQualityOrAlbumCannotPretendToBeSameSearch() {
        val history=A0SearchComparison()
        history.observe("ILLIT Magnetic","Album","HIGH_QUALITY",25,5)
        assertEquals(A0SearchComparison.Kind.CHANGED,
            history.observe("ILLIT Magnetic","Album","LOSSLESS",0,0).kind)
        assertEquals(A0SearchComparison.Kind.CHANGED,
            history.observe("ILLIT Magnetic","Different","LOSSLESS",0,0).kind)
    }
    @Test fun repeatRecoveryAndPersistedQueriesAreNeverImplied() {
        val history=A0SearchComparison()
        history.observe("Artist - Title",null,"HIGH_QUALITY",0,0)
        assertEquals(A0SearchComparison.Kind.REPEAT_GAINED,
            history.observe("artist - title",null,"HIGH_QUALITY",16,3).kind)
        assertEquals(A0SearchComparison.Kind.REPEAT_STABLE,
            history.observe("artist - title",null,"HIGH_QUALITY",4,2).kind)
        assertEquals(A0SearchComparison.Kind.CHANGED,
            A0SearchComparison().observe("artist - title",null,"HIGH_QUALITY",1,1)
                .kind.let { if(it==A0SearchComparison.Kind.FIRST)
                    A0SearchComparison.Kind.CHANGED else it })
        assertFalse(history.toString().contains("Title"))
    }
}
