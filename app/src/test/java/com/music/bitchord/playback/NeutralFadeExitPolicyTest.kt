package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NeutralFadeExitPolicyTest {
    @Test
    fun `switching off before handoff or after handoff uses smooth bail`() {
        for (progress in listOf(0f, 0.2f, 0.49f, 0.8f, 0.99f)) {
            assertEquals(
                FadeExit.BAIL,
                NeutralFadeExitPolicy.decide(progress, tailDone = true, settingSwitchedOff = true),
            )
        }
    }

    @Test
    fun `ordinary transition remains until its fade and tail are done`() {
        assertEquals(
            FadeExit.CONTINUE,
            NeutralFadeExitPolicy.decide(0.7f, tailDone = true, settingSwitchedOff = false),
        )
        assertEquals(
            FadeExit.CONTINUE,
            NeutralFadeExitPolicy.decide(1f, tailDone = false, settingSwitchedOff = false),
        )
        assertEquals(
            FadeExit.FINISH,
            NeutralFadeExitPolicy.decide(1f, tailDone = true, settingSwitchedOff = false),
        )
    }

    @Test
    fun `disabling after completion finishes rather than reopening outgoing track`() {
        assertEquals(
            FadeExit.FINISH,
            NeutralFadeExitPolicy.decide(1f, tailDone = true, settingSwitchedOff = true),
        )
    }
}
