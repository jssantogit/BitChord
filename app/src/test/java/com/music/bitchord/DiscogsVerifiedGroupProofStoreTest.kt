package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import org.junit.Assert.*
import org.junit.Test

class DiscogsVerifiedGroupProofStoreTest {
    private val group = "00000000-0000-4000-8000-000000000123"

    @Test fun onlyIndependentlyVerifiedReleaseEvidenceCanAffectAnExactGroup() {
        val store = DiscogsVerifiedGroupProofStore()
        store.record(group, 101, DiscogsEditorialKind.EP, verified = false, expiresAtMillis = 10_000, nowMillis = 1_000)
        assertTrue(store.activeProofs(1_001).isEmpty())

        store.record(group, 101, DiscogsEditorialKind.EP, verified = true, expiresAtMillis = 10_000, nowMillis = 1_000)
        val one = store.activeProofs(1_001)[group].orEmpty()
        assertEquals(1, one.size)
        assertEquals(101L, one.single().releaseId)
        assertTrue(DiscogsTypeConsensus.resolve("Album", one) is DiscogsTypeResolution.Disputed)

        store.record(group, 102, DiscogsEditorialKind.EP, verified = true, expiresAtMillis = 10_000, nowMillis = 1_001)
        val two = store.activeProofs(1_002)[group].orEmpty()
        val corrected = DiscogsTypeConsensus.resolve("Album", two)
        assertEquals(DiscogsTypeSource.DISCOGS_CORRECTION, (corrected as DiscogsTypeResolution.Decided).source)
        assertEquals(DiscogsEditorialKind.EP, corrected.kind)
    }

    @Test fun noCrossGroupMixingExpiredEvidenceOrInvalidIdentity() {
        val store = DiscogsVerifiedGroupProofStore()
        store.record(group, 101, DiscogsEditorialKind.EP, true, 2_000, 1_000)
        store.record("invalid", 102, DiscogsEditorialKind.EP, true, 2_000, 1_000)
        store.record(group, 103, DiscogsEditorialKind.ALBUM, true, 1_000, 1_000)
        assertEquals(listOf(101L), store.activeProofs(1_001)[group]?.map { it.releaseId })
        assertTrue(store.activeProofs(2_000).isEmpty())
        store.prune(2_000)
        assertTrue(store.proofs.value.isEmpty())
    }

    @Test fun storeRetainsMasterScopeForIndependentVoteThreshold() {
        val store = DiscogsVerifiedGroupProofStore()
        store.record(group, 101, DiscogsEditorialKind.EP, true, 10_000, 1_000,
            masterId = 55)
        store.record(group, 102, DiscogsEditorialKind.EP, true, 10_000, 1_001,
            masterId = 55)
        assertEquals(listOf(55L, 55L),
            store.activeProofs(1_002)[group]?.map { it.masterId })
        assertTrue(DiscogsTypeConsensus.resolve(
            "Album", store.activeProofs(1_002)[group].orEmpty()
        ) is DiscogsTypeResolution.Disputed)
    }

    @Test fun reobservingSameReleaseCannotInventTwoIndependentVotes() {
        val store = DiscogsVerifiedGroupProofStore()
        store.record(group, 101, DiscogsEditorialKind.EP, true, 10_000, 1_000)
        store.record(group, 101, DiscogsEditorialKind.EP, true, 10_000, 1_001)
        assertEquals(1, store.activeProofs(1_002)[group]?.size)
    }
}
