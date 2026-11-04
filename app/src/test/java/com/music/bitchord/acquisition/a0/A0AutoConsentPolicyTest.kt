package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0AutoConsentPolicyTest {
    @Test fun explicitDownloadTapIsNotEquivalentToEnablingAutoSelection() {
        assertFalse(A0AutoConsentPolicy.allows(
            userRequested=true,enabledByUser=false,devPilot=true,
            downloadsAllowed=true,destinationReady=true))
    }
    @Test fun missingDestinationOfflinePolicyOrNonPilotNeverUsesNetwork() {
        for(disabled in 0..4) {
            val flags=MutableList(5){true}.also {it[disabled]=false}
            assertFalse(A0AutoConsentPolicy.allows(
                flags[0],flags[1],flags[2],flags[3],flags[4]))
        }
        assertTrue(A0AutoConsentPolicy.allows(true,true,true,true,true))
    }
}
