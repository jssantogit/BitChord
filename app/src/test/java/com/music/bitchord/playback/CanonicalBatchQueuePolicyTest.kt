package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import org.junit.Assert.*
import org.junit.Test

class CanonicalBatchQueuePolicyTest {
    private fun plan(
        intent: CanonicalCollectionIntent,
        vararg ids: String,
    ): CanonicalCollectionIntentPlan = CanonicalCollectionIntentPlan(
        intent = intent,
        selections = ids.map { id ->
            CanonicalCollectionSelection(CanonicalQueueIdentity(id, "asset-$id", "opensubsonic:home"), id, "Artist")
        },
        rejected = emptyList(),
    )

    private fun snapshot(
        current: Int,
        vararg tiers: QueueTier,
        generation: Long = 7L,
        transitioning: Boolean = false,
    ): CanonicalBatchQueueSnapshot = CanonicalBatchQueueSnapshot(
        currentIndex = current,
        contextGeneration = generation,
        queueKeys = tiers.indices.map { "entry-$it" },
        tiers = tiers.toList(),
        transitioning = transitioning,
    )

    @Test fun `add next inserts before existing user queue without playback`() {
        val p = plan(CanonicalCollectionIntent.ADD_NEXT, "a", "b")
        val state = snapshot(1, QueueTier.CONTEXT, QueueTier.CONTEXT,
            QueueTier.USER_QUEUE, QueueTier.USER_QUEUE, QueueTier.CONTEXT, QueueTier.AUTOPLAY)
        val result = CanonicalBatchQueuePolicy.decide(p, state, state, p.selections)
        val edit = result as CanonicalBatchQueueDecision.Apply
        assertEquals(2, edit.insertionIndex)
        assertEquals(listOf(QueueTier.USER_QUEUE, QueueTier.USER_QUEUE), edit.tiers)
        assertFalse(edit.startPlayback)
    }

    @Test fun `append remains behind user queue and ahead of context and autoplay`() {
        val p = plan(CanonicalCollectionIntent.ADD_TO_QUEUE, "a", "b")
        val state = snapshot(0, QueueTier.CONTEXT, QueueTier.USER_QUEUE,
            QueueTier.USER_QUEUE, QueueTier.CONTEXT, QueueTier.AUTOPLAY)
        val edit = CanonicalBatchQueuePolicy.decide(p, state, state, p.selections) as CanonicalBatchQueueDecision.Apply
        assertEquals(3, edit.insertionIndex)
        assertFalse(edit.startPlayback)
    }

    @Test fun `play now inserts after current without deleting history or pending tiers`() {
        val p = plan(CanonicalCollectionIntent.PLAY_NOW, "a", "b")
        val state = snapshot(2, QueueTier.CONTEXT, QueueTier.USER_QUEUE, QueueTier.CONTEXT,
            QueueTier.USER_QUEUE, QueueTier.CONTEXT)
        val edit = CanonicalBatchQueuePolicy.decide(p, state, state, p.selections) as CanonicalBatchQueueDecision.Apply
        assertEquals(3, edit.insertionIndex)
        assertEquals(listOf(QueueTier.CONTEXT, QueueTier.USER_QUEUE), edit.tiers)
        assertTrue(edit.startPlayback)
    }

    @Test fun `empty queue add does not silently start and play now is explicit`() {
        val empty = snapshot(-1)
        val add = plan(CanonicalCollectionIntent.ADD_TO_QUEUE, "a")
        val append = CanonicalBatchQueuePolicy.decide(add, empty, empty, add.selections) as CanonicalBatchQueueDecision.Apply
        assertEquals(0, append.insertionIndex)
        assertFalse(append.startPlayback)
        val play = plan(CanonicalCollectionIntent.PLAY_NOW, "a")
        assertTrue((CanonicalBatchQueuePolicy.decide(play, empty, empty, play.selections)
            as CanonicalBatchQueueDecision.Apply).startPlayback)
    }

    @Test fun `stale timeline or generation rejects whole batch without mutation`() {
        val p = plan(CanonicalCollectionIntent.ADD_NEXT, "a", "b")
        val original = snapshot(0, QueueTier.CONTEXT, QueueTier.USER_QUEUE)
        val changed = snapshot(0, QueueTier.CONTEXT, QueueTier.USER_QUEUE, generation = 8)
        assertEquals(CanonicalBatchQueueFailure.STALE_SESSION,
            (CanonicalBatchQueuePolicy.decide(p, original, changed, p.selections)
                as CanonicalBatchQueueDecision.Reject).reason)
        val newQueue = original.copy(queueKeys = listOf("other", "entry-1"))
        assertEquals(CanonicalBatchQueueFailure.STALE_SESSION,
            (CanonicalBatchQueuePolicy.decide(p, original, newQueue, p.selections)
                as CanonicalBatchQueueDecision.Reject).reason)
    }

    @Test fun `asset disappearance or changed rendition rejects all planned entries`() {
        val p = plan(CanonicalCollectionIntent.ADD_TO_QUEUE, "a", "b")
        val state = snapshot(0, QueueTier.CONTEXT)
        val changedAsset = p.selections[1].copy(identity = CanonicalQueueIdentity("b", "other-asset", "opensubsonic:home"))
        val result = CanonicalBatchQueuePolicy.decide(p, state, state, listOf(p.selections[0], changedAsset))
        assertEquals(CanonicalBatchQueueFailure.AVAILABILITY_CHANGED,
            (result as CanonicalBatchQueueDecision.Reject).reason)
    }

    @Test fun `crossfade active rejects unsafe immediate edits and preserves current`() {
        val p = plan(CanonicalCollectionIntent.PLAY_NOW, "a")
        val s = snapshot(0, QueueTier.CONTEXT, QueueTier.CONTEXT, transitioning = true)
        val result = CanonicalBatchQueuePolicy.decide(p, s, s, p.selections)
        assertEquals(CanonicalBatchQueueFailure.TRANSITION_ACTIVE,
            (result as CanonicalBatchQueueDecision.Reject).reason)
    }

    @Test fun `request token can only be consumed once even on repeated callbacks`() {
        val p = plan(CanonicalCollectionIntent.ADD_NEXT, "a")
        val token = CanonicalBatchQueueRequests.submit(p)
        assertEquals(p, CanonicalBatchQueueRequests.take(token))
        assertNull(CanonicalBatchQueueRequests.take(token))
        assertNull(CanonicalBatchQueueRequests.take("unknown-token"))
    }

    @Test fun `partially rejected preview never inserts secretly`() {
        val p = plan(CanonicalCollectionIntent.ADD_NEXT, "a").copy(
            rejected = listOf(CanonicalCollectionRejected(1, CollectionIntentRejection.NO_ASSET)))
        val s = snapshot(0, QueueTier.CONTEXT)
        assertEquals(CanonicalBatchQueueFailure.PARTIAL_SELECTION,
            (CanonicalBatchQueuePolicy.decide(p, s, s, p.selections)
                as CanonicalBatchQueueDecision.Reject).reason)
    }
}
