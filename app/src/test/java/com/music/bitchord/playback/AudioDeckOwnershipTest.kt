package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audible-sink role belongs to a physical player, never to a DSP filter. */
class AudioDeckOwnershipTest {
    @Test
    fun `first player is audible and prebuffered standby is silent in diagnostics`() {
        val owner = AudioDeckOwnership()
        assertTrue(owner.isAudible(deckA = true))
        assertFalse(owner.isAudible(deckA = false))
    }

    @Test
    fun `automix and version swaps move sink telemetry with current player`() {
        val owner = AudioDeckOwnership()
        owner.handoff()
        assertFalse(owner.isAudible(deckA = true))
        assertTrue(owner.isAudible(deckA = false))
        owner.handoff()
        assertTrue(owner.isAudible(deckA = true))
        assertFalse(owner.isAudible(deckA = false))
    }

    @Test
    fun `rebuilding both players resets the owner to first deck`() {
        val owner = AudioDeckOwnership()
        owner.handoff()
        owner.reset()
        assertTrue(owner.isAudible(deckA = true))
        assertFalse(owner.isAudible(deckA = false))
    }
}
