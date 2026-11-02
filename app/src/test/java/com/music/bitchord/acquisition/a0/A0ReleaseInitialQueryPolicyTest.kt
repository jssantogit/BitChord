package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0ReleaseInitialQueryPolicyTest {
    @Test fun releaseWithMultipleMissingTracksSearchesArtistAlbumFirst() {
        val initial=A0ReleaseInitialQueryPolicy.choose(
            "BTS Epiphany Jin Demo Ver","BTS Proof",48,emptySet())
        assertEquals(A0ReleaseInitialQueryPolicy.Kind.ALBUM_WIDE,initial.kind)
        assertEquals("BTS Proof",initial.query)
        // The album was sent; subsequent sessions search the actual track,
        // and the already sent album query never consumes a fallback slot.
        val next=A0ReleaseInitialQueryPolicy.choose(
            "BTS Seesaw Demo Ver","BTS Proof",48,setOf(initial.query))
        assertEquals(A0ReleaseInitialQueryPolicy.Kind.TRACK,next.kind)
        assertEquals("BTS Seesaw Demo Ver",next.query)
        val recovery=A0SearchRecoveryPlan("BTS - Epiphany (Jin Demo Ver.)",
            "Proof",releaseWide=true)
        assertNotEquals("BTS Proof",recovery.next(6000,false,
            setOf("BTS Proof"),excludedQueries=setOf("BTS Proof")))
    }

    @Test fun aMixedBatchDoesNotPretendEverySongSharesOneRelease() {
        val mixed=listOf(
            com.music.bitchord.download.SoulseekBatchPlan.Entry(
                "a","Artist - First","EP",180),
            com.music.bitchord.download.SoulseekBatchPlan.Entry(
                "b","Other - Second","EP",180),
            com.music.bitchord.download.SoulseekBatchPlan.Entry(
                "c","Artist - Third","Different EP",180),
            com.music.bitchord.download.SoulseekBatchPlan.Entry(
                "d","artist - Fourth","ep",180))
        val cohort=A0ReleaseInitialQueryPolicy.cohortSize(mixed[0],mixed)
        assertEquals(2,cohort)
        assertEquals(A0ReleaseInitialQueryPolicy.Kind.TRACK,
            A0ReleaseInitialQueryPolicy.choose(
                "Artist First","Artist EP",cohort,emptySet()).kind)
        val same=mixed.take(1)+List(3) { index ->
            com.music.bitchord.download.SoulseekBatchPlan.Entry(
                "extra-$index","Artist - Extra $index","EP",180)
        }
        assertEquals(4,A0ReleaseInitialQueryPolicy.cohortSize(same[0],same))
    }

    @Test fun soloShortReleaseOrMissingAlbumStaysTrackSpecific() {
        for(size in 1..3) {
            assertEquals(A0ReleaseInitialQueryPolicy.Kind.TRACK,
                A0ReleaseInitialQueryPolicy.choose(
                    "Artist Song","Artist Album",size,emptySet()).kind)
        }
        assertEquals(A0ReleaseInitialQueryPolicy.Kind.TRACK,
            A0ReleaseInitialQueryPolicy.choose(
                "Artist Song",null,48,emptySet()).kind)
        assertEquals(A0ReleaseInitialQueryPolicy.Kind.TRACK,
            A0ReleaseInitialQueryPolicy.choose(
                "Artist Song","Artist Song",48,emptySet()).kind)
        assertEquals(A0ReleaseInitialQueryPolicy.Kind.TRACK,
            A0ReleaseInitialQueryPolicy.choose(
                "Artist Song","  ",48,emptySet()).kind)
    }
}
