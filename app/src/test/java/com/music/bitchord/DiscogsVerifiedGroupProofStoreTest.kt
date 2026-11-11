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

    @Test fun miniAlbumCanFillMissingEpTypeAndMaxiSingleCanFillSingleType() {
        val store = DiscogsVerifiedGroupProofStore()
        val group2 = "00000000-0000-4000-8000-000000000888"
        store.record(group, 701, DiscogsEditorialKind.MINI_ALBUM,
            true, 10_000, 1_000, masterId = 700)
        store.record(group2, 801, DiscogsEditorialKind.MAXI_SINGLE,
            true, 10_000, 1_000, masterId = 800)
        val proofs = store.activeProofs(1_001)
        assertEquals(DiscogsEditorialKind.EP,
            (DiscogsTypeConsensus.resolve("Other", proofs[group].orEmpty())
                as DiscogsTypeResolution.Decided).kind)
        assertEquals(DiscogsEditorialKind.SINGLE,
            (DiscogsTypeConsensus.resolve("Other", proofs[group2].orEmpty())
                as DiscogsTypeResolution.Decided).kind)
    }

    @Test fun mixtapeIsNotAutomaticallyReclassifiedAsAlbum() {
        val store = DiscogsVerifiedGroupProofStore()
        store.record(group, 900, DiscogsEditorialKind.MIXTAPE,
            true, 10_000, 1_000)
        assertTrue(store.activeProofs(1_001).isEmpty())
    }

    @Test fun conflictingReobservationsQuarantineReleaseInsteadOfOverwritingProof() {
        val store = DiscogsVerifiedGroupProofStore()
        store.record(group, 101, DiscogsEditorialKind.EP, true, 10_000, 1_000,
            masterId = 500)
        store.record(group, 102, DiscogsEditorialKind.EP, true, 10_000, 1_000,
            masterId = 600)
        assertEquals(DiscogsTypeSource.DISCOGS_CORRECTION,
            (DiscogsTypeConsensus.resolve("Album",
                store.activeProofs(1_001)[group].orEmpty())
                as DiscogsTypeResolution.Decided).source)

        // The same Release now reports a different editorial kind. Do not
        // allow old or new to serve as an independent proof until expiry.
        store.record(group, 101, DiscogsEditorialKind.ALBUM, true, 10_000, 1_002,
            masterId = 500)
        assertEquals(listOf(102L), store.activeProofs(1_003)[group]?.map { it.releaseId })
        assertTrue(DiscogsTypeConsensus.resolve("Album",
            store.activeProofs(1_003)[group].orEmpty()
        ) is DiscogsTypeResolution.Disputed)

        // Re-observation must not silently unquarantine disputed evidence.
        store.record(group, 101, DiscogsEditorialKind.EP, true, 10_000, 1_004,
            masterId = 500)
        assertEquals(listOf(102L), store.activeProofs(1_005)[group]?.map { it.releaseId })
        store.prune(10_000)
        assertTrue(store.activeProofs(10_000).isEmpty())
        store.record(group, 101, DiscogsEditorialKind.EP, true, 20_000, 10_000,
            masterId = 500)
        assertEquals(listOf(101L), store.activeProofs(10_001)[group]?.map { it.releaseId })
    }

    @Test fun conflictingMastersAreQuarantinedAndMissingMasterNeverErasesKnownMaster() {
        val store = DiscogsVerifiedGroupProofStore()
        store.record(group, 101, DiscogsEditorialKind.EP, true, 10_000, 1_000,
            masterId = 500)
        store.record(group, 101, DiscogsEditorialKind.EP, true, 10_000, 1_001)
        assertEquals(500L, store.activeProofs(1_002)[group]?.single()?.masterId)
        store.record(group, 102, DiscogsEditorialKind.EP, true, 10_000, 1_002,
            masterId = 500)
        assertTrue(DiscogsTypeConsensus.resolve("Album",
            store.activeProofs(1_003)[group].orEmpty()
        ) is DiscogsTypeResolution.Disputed)

        store.record(group, 101, DiscogsEditorialKind.EP, true, 10_000, 1_003,
            masterId = 600)
        assertEquals(listOf(102L), store.activeProofs(1_004)[group]?.map { it.releaseId })
        assertTrue(DiscogsTypeConsensus.resolve("Album",
            store.activeProofs(1_004)[group].orEmpty()
        ) is DiscogsTypeResolution.Disputed)
    }

    @Test fun reobservingSameReleaseCannotInventTwoIndependentVotes() {
        val store = DiscogsVerifiedGroupProofStore()
        store.record(group, 101, DiscogsEditorialKind.EP, true, 10_000, 1_000)
        store.record(group, 101, DiscogsEditorialKind.EP, true, 10_000, 1_001)
        assertEquals(1, store.activeProofs(1_002)[group]?.size)
    }
}
