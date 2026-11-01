package com.music.bitchord.acquisition.a0

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class A0PeerWorkStageCountsTest {
    @Test fun countsLiveStagesAndReleasesOnFailureWithoutRecordingIdentities() {
        val stages=A0PeerWorkStageCounts()
        assertEquals("connecting=0 reading=0 publishing=0",stages.summary())
        stages.inStage(A0PeerWorkStageCounts.Stage.CONNECTING) {
            assertEquals("connecting=1 reading=0 publishing=0",stages.summary())
        }
        try {
            stages.inStage(A0PeerWorkStageCounts.Stage.READING) {
                stages.inStage(A0PeerWorkStageCounts.Stage.PUBLISHING) {
                    assertEquals("connecting=0 reading=1 publishing=1",stages.summary())
                    throw IOException("fixture")
                }
            }
        }catch(_:IOException){}
        assertEquals("connecting=0 reading=0 publishing=0",stages.summary())
    }
}
