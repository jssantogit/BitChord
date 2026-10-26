package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

class SoulseekBatchPlanTest {
    private fun song(id:String,title:String,album:String?=null)=
        SoulseekBatchPlan.SourceTrack(id, "ILLIT",title,listOf("ILLIT"),album,160)

    @Test fun releaseWithFourTracksBecomesOneBoundedManualBatchInDiscOrder() {
        val plan=SoulseekBatchPlan.prepare(listOf(
            song("1","My World"),song("2","Magnetic"),song("3","Midnight Fiction"),song("4","Lucky Girl Syndrome")),
            "SUPER REAL ME",isRelease=true,pilotAvailable=true)
        assertNotNull(plan)
        assertEquals(4,plan!!.size)
        assertEquals(listOf("ILLIT - My World","ILLIT - Magnetic",
            "ILLIT - Midnight Fiction","ILLIT - Lucky Girl Syndrome"),plan.map{it.query})
        assertTrue(plan.all{it.album=="SUPER REAL ME"})
        assertEquals(listOf("1","2","3","4"),plan.map{it.trackId})
    }

    @Test fun singlesWithMultipleTracksAndReprisesArePreserved() {
        val plan=SoulseekBatchPlan.prepare(listOf(
            song("1","Title", "Single"),
            song("2","Title", "Single"),
            song("3","Instrumental", "Single")
        ),"Single",true,true)
        assertEquals(3,plan!!.size)
        assertEquals(listOf("1","2","3"),plan.map{it.trackId})
    }

    @Test fun failsClosedForPlaylistMissingTracksAndUnboundedInput() {
        val valid=listOf(song("1","Magnetic"),song("2","Midnight Fiction"))
        assertNull(SoulseekBatchPlan.prepare(valid,"playlist",false,true))
        assertNull(SoulseekBatchPlan.prepare(valid,"album",true,false))
        assertNull(SoulseekBatchPlan.prepare(valid+song("3",""),"album",true,true))
        assertNull(SoulseekBatchPlan.prepare((1..65).map{song("$it","Track $it")},"album",true,true))
        assertNull(SoulseekBatchPlan.prepare(listOf(song("1","One")),"album",true,true))
    }

    @Test fun batchProgressRequiresOneExplicitDecisionPerTrack() {
        val plan=SoulseekBatchPlan.prepare(listOf(song("1","One"),song("2","Two"),
            song("3","Three")),"Album",true,true)!!
        val session=SoulseekBatchSession(plan)
        assertEquals(0,session.index)
        assertEquals(0,session.succeeded)
        assertTrue(session.markSaved())
        assertFalse(session.markSaved()) // no double credit
        assertTrue(session.advance())
        assertEquals("ILLIT - Two",session.current!!.query)
        assertTrue(session.advance()) // explicit skip
        assertEquals(1,session.skipped)
        assertTrue(session.markSaved())
        assertFalse(session.advance())
        assertTrue(session.isComplete)
        assertEquals(2,session.succeeded)
        assertEquals(1,session.skipped)
    }
    @Test fun editingWireQueryDoesNotChangeAlbumTrackIdentityForRanking() {
        val entry=SoulseekBatchPlan.Entry("2","ILLIT - Magnetic","SUPER REAL ME",161)
        assertEquals("ILLIT - Magnetic",
            SoulseekBatchPlan.identityQuery(entry,"Magnetic 320"))
        assertEquals("Magnetic 320",
            SoulseekBatchPlan.identityQuery(null,"Magnetic 320"))
    }


    @Test fun batchExpectedDurationAlwaysComesFromCurrentCanonicalSlot() {
        val entries=listOf(
            SoulseekBatchPlan.Entry("1","ILLIT - Intro","BREAK EVEN",126),
            SoulseekBatchPlan.Entry("2","ILLIT - More","BREAK EVEN",183),
            SoulseekBatchPlan.Entry("3","ILLIT - Another","BREAK EVEN",null)
        )
        val session=SoulseekBatchSession(entries)
        assertEquals(126,SoulseekBatchPlan.expectedDuration(session.current,126))
        assertTrue(session.advance())
        // A stale single-song Intent from the first track cannot override
        // the second track's true duration or poison its preflight.
        assertEquals(183,SoulseekBatchPlan.expectedDuration(session.current,126))
        assertTrue(session.advance())
        // Missing canonical duration must abstain, not inherit a prior slot.
        assertNull(SoulseekBatchPlan.expectedDuration(session.current,126))
        assertEquals(199,SoulseekBatchPlan.expectedDuration(null,199))
        assertNull(SoulseekBatchPlan.expectedDuration(null,0))
        assertNull(SoulseekBatchPlan.expectedDuration(null,9000))
    }
}
