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
