package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio-telemetry ownership is specific to a physical player generation. */
class AudioDeckOwnershipTest {
    @Test
    fun `first player is audible and prebuffered standby is silent in diagnostics`() {
        val owner = AudioDeckOwnership()
        val generation = owner.generation()
        assertTrue(owner.isAudible(deckA = true, generation = generation))
        assertFalse(owner.isAudible(deckA = false, generation = generation))
    }

    @Test
    fun `automix and version swaps move sink telemetry with current player`() {
        val owner = AudioDeckOwnership()
        val generation = owner.generation()
        owner.handoff()
        assertFalse(owner.isAudible(deckA = true, generation = generation))
        assertTrue(owner.isAudible(deckA = false, generation = generation))
        owner.handoff()
        assertTrue(owner.isAudible(deckA = true, generation = generation))
        assertFalse(owner.isAudible(deckA = false, generation = generation))
    }

    @Test
    fun `rebuilding both players resets owner and invalidates the old sinks`() {
        val owner = AudioDeckOwnership()
        val oldGeneration = owner.generation()
        owner.handoff()
        owner.reset()
        val newGeneration = owner.generation()
        assertTrue(newGeneration != oldGeneration)
        assertTrue(owner.isAudible(deckA = true, generation = newGeneration))
        assertFalse(owner.isAudible(deckA = false, generation = newGeneration))
        assertFalse("old active sink cannot publish stale telemetry",
            owner.isAudible(deckA = true, generation = oldGeneration))
        assertFalse("old standby sink cannot publish stale telemetry",
            owner.isAudible(deckA = false, generation = oldGeneration))
    }

    @Test
    fun `old standby cannot become audible on future handoff after rebuild`() {
        val owner = AudioDeckOwnership()
        val oldGeneration = owner.generation()
        owner.reset()
        val newGeneration = owner.generation()
        owner.handoff()
        assertTrue(owner.isAudible(deckA = false, generation = newGeneration))
        assertFalse(owner.isAudible(deckA = false, generation = oldGeneration))
        assertFalse(owner.isAudible(deckA = true, generation = oldGeneration))
    }
}
