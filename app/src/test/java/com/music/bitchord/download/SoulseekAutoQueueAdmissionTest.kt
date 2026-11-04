package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

class SoulseekAutoQueueAdmissionTest {
    private fun entry(id:String="rec-1",query:String="ILLIT - Magnetic")=
        SoulseekBatchPlan.Entry(id,query,"SUPER REAL ME",180)

    @Test fun optInIsSeparateFromDownloadTapAndRequiresPilotAndNetworkPolicy() {
        for (absent in 0..3) {
            val enabled=MutableList(4){true}.also{it[absent]=false}
            assertFalse(SoulseekAutoQueueAdmission.allows(
                listOf(entry()),enabled[0],enabled[1],enabled[2],enabled[3]))
        }
        assertTrue(SoulseekAutoQueueAdmission.allows(
            listOf(entry()),true,true,true,true))
    }

    @Test fun releaseMustBeBoundedAndHaveDistinctNonEmptySlots() {
        assertFalse(SoulseekAutoQueueAdmission.allows(emptyList(),true,true,true,true))
        assertFalse(SoulseekAutoQueueAdmission.allows(
            List(65){entry("rec-$it")},true,true,true,true))
        assertFalse(SoulseekAutoQueueAdmission.allows(
            listOf(entry(),entry()),true,true,true,true))
        assertTrue(SoulseekAutoQueueAdmission.allows(
            listOf(entry(),entry("rec-2","ILLIT - Lucky Girl Syndrome")),
            true,true,true,true))
    }

    @Test fun meteredPolicyRefusesAutomaticEnqueueWithSpecificReason() {
        val entries=listOf(entry())
        assertEquals(SoulseekAutoQueueAdmission.Refusal.NETWORK_RESTRICTED,
            SoulseekAutoQueueAdmission.refusal(entries,true,true,true,false))
        assertFalse(SoulseekAutoQueueAdmission.allows(entries,true,true,true,false))
        assertNull(SoulseekAutoQueueAdmission.refusal(entries,true,true,true,true))
    }

    @Test fun duplicateIdsAndMalformedMetadataHaveDifferentRefusalReasons() {
        assertEquals(SoulseekAutoQueueAdmission.Refusal.DUPLICATE_TRACK_IDS,
            SoulseekAutoQueueAdmission.refusal(
                listOf(entry(),entry()),true,true,true,true))
        assertEquals(SoulseekAutoQueueAdmission.Refusal.INVALID_TRACK_METADATA,
            SoulseekAutoQueueAdmission.refusal(
                listOf(entry(query=" ")),true,true,true,true))
    }

    @Test fun malformedIntentMetadataNeverEntersTheServiceQueue() {
        val invalid=listOf(
            entry(query=" "),
            entry(query="a".repeat(121)),
            entry(id=""),
            entry(id="a".repeat(181)),
            entry(query="ILLIT\u0000 Magnetic"),
            entry().copy(album="x".repeat(121)),
            entry().copy(durationSeconds=14),
            entry().copy(durationSeconds=7201),
        )
        for (item in invalid) assertFalse(SoulseekAutoQueueAdmission.allows(
            listOf(item),true,true,true,true))
    }
}
