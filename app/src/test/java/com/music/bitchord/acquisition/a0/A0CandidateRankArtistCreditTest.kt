package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Exact artist+album directory credit must be independent of title tokens. */
class A0CandidateRankArtistCreditTest {
    private fun rank(folder:String):A0CandidateRank.Ranking =
        A0CandidateRank.rank(
            searchPhrase="Queen - Queen Of Hearts",
            name="Queen Of Hearts.flac",
            declaredBitrateKbps=900,
            remotePath="Shared/$folder/Queen Of Hearts.flac",
            format="flac",
            declaredDurationSeconds=220,
            expectedDurationSeconds=220,
            expectedAlbum="The Works",
            sizeBytes=27_000_000L,
        )

    @Test fun titleOverlappingArtistCanUseExactArtistAndAlbumDirectoryAsIndependentCredit() {
        val result=rank("Queen - The Works")
        assertEquals(A0CandidateRank.Match.ALL_TERMS,result.match)
        assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in result.warnings)
        assertFalse(A0CandidateRank.Warning.ARTIST_CONFLICT in result.warnings)
    }

    @Test fun titleAloneOrAlbumOnlyMustNotFabricateArtistCredit() {
        for(folder in listOf("The Works","Other Artist - The Works",
            "Queen - Other Album","Queen - The Works - Remastered",
            "Shared Queen Archive - The Works")) {
            val result=rank(folder)
            assertTrue("Untrusted folder: $folder",
                result.match!=A0CandidateRank.Match.ALL_TERMS ||
                    A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in result.warnings)
        }
    }

    @Test fun overlappingTitleCannotBorrowCreditFromUnrelatedFolderSegments() {
        val result=A0CandidateRank.rank(
            searchPhrase="Queen - Queen Of Hearts",
            name="Queen Of Hearts.flac",declaredBitrateKbps=900,
            remotePath="Shared/Queen Archives/The Works/Queen Of Hearts.flac",
            format="flac",declaredDurationSeconds=220,
            expectedDurationSeconds=220,expectedAlbum="The Works")
        assertTrue(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in result.warnings)
    }
}
