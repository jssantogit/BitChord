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
    @Test fun missingArtistCreditDistinguishesAbsentPartialAndSplitDirectories() {
        fun classify(path:String,name:String="Ditto.flac") =
            A0CandidateRank.rank(searchPhrase="New Jeans - Ditto",name=name,
                declaredBitrateKbps=900,remotePath=path,format="flac",
                declaredDurationSeconds=220,expectedDurationSeconds=220,
                expectedAlbum="Get Up").artistCreditGap
        assertEquals(A0CandidateRank.ArtistCreditGap.ABSENT,
            classify("Shared/Other/Get Up/Ditto.flac"))
        assertEquals(A0CandidateRank.ArtistCreditGap.PARTIAL_FILENAME,
            classify("Shared/Other/Get Up/New - Ditto.flac","New - Ditto.flac"))
        assertEquals(A0CandidateRank.ArtistCreditGap.PARTIAL_DIRECTORY,
            classify("Shared/New Collection/Get Up/Ditto.flac"))
        assertEquals(A0CandidateRank.ArtistCreditGap.SPLIT_DIRECTORIES,
            classify("Shared/New/Jeans/Get Up/Ditto.flac"))
        assertEquals(A0CandidateRank.ArtistCreditGap.NONE,
            classify("Shared/New Jeans/Get Up/Ditto.flac"))
    }

    @Test fun joinedArtistWordsAreValidOnlyAsAnExplicitFilenameCreditOrExactFolder() {
        fun compact(file:String,path:String)=A0CandidateRank.rank(
            searchPhrase="BLACK PINK - How You Like That",
            name=file,declaredBitrateKbps=320,remotePath=path,
            format="mp3",declaredDurationSeconds=220,
            expectedDurationSeconds=220,expectedAlbum="THE ALBUM",
            sizeBytes=8_800_000L)
        val explicit=compact("BLACKPINK - How You Like That.mp3",
            "Shared/THE ALBUM/BLACKPINK - How You Like That.mp3")
        assertEquals(A0CandidateRank.Match.ALL_TERMS,explicit.match)
        assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in explicit.warnings)
        assertFalse(A0CandidateRank.Warning.ARTIST_CONFLICT in explicit.warnings)
        val directory=compact("How You Like That.mp3",
            "Shared/BLACKPINK/THE ALBUM/How You Like That.mp3")
        assertEquals(A0CandidateRank.Match.ALL_TERMS,directory.match)
        assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in directory.warnings)
    }

    @Test fun joinedArtistTextInsideTitleOrUnrelatedCreditsCannotAuthenticate() {
        fun rank(file:String,folder:String="THE ALBUM")=
            A0CandidateRank.rank(
                searchPhrase="BLACK PINK - How You Like That",
                name=file,declaredBitrateKbps=320,
                remotePath="Shared/$folder/$file",format="mp3",
                declaredDurationSeconds=220,expectedDurationSeconds=220,
                expectedAlbum="THE ALBUM",sizeBytes=8_800_000L)
        for(file in listOf("How You Like That BLACKPINK.mp3",
            "PINKBLACK - How You Like That.mp3",
            "Other BLACKPINK - How You Like That.mp3")) {
            val candidate=rank(file)
            assertTrue("Credit not explicit: $file",
                candidate.match!=A0CandidateRank.Match.ALL_TERMS ||
                    A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in candidate.warnings ||
                    A0CandidateRank.Warning.ARTIST_CONFLICT in candidate.warnings)
        }
        val unrelated=rank("How You Like That.mp3","My BLACKPINK Archive")
        assertNotEquals(A0CandidateRank.Match.ALL_TERMS,unrelated.match)
    }

}
