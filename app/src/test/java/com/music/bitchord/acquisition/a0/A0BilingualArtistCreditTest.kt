package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Canonical bilingual credits only: never infer an artist from a song title. */
class A0BilingualArtistCreditTest {
    private fun rank(
        artist:String="BTS (방탄소년단)",
        name:String="BTS - Boy In Luv.flac",
        folder:String="Proof",
    )=A0CandidateRank.rank(
        searchPhrase="$artist - Boy In Luv",
        name=name,declaredBitrateKbps=900,
        remotePath="Shared/$folder/$name",format="flac",
        declaredDurationSeconds=240,expectedDurationSeconds=240,
        expectedAlbum="Proof",sizeBytes=29_000_000L
    )

    private fun album(
        name:String,artist:String="BTS (방탄소년단)",
        mbid:String?=null,
        source:A0CanonicalAlbumPolicy.ClaimSource=
            A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT
    )=A0CanonicalAlbumPolicy.assess(
        A0CanonicalAlbumPolicy.Target("Proof",exactReleaseMbid=mbid,
            expectedArtist=artist),
        A0CanonicalAlbumPolicy.Claim(name,source)
    )

    @Test fun explicitPrimaryArtistPrefixCanCreditEitherCanonicalBilingualOrder() {
        for(artist in listOf("BTS (방탄소년단)","방탄소년단 (BTS)")) {
            val ranked=rank(artist)
            assertEquals(artist,A0CandidateRank.Match.ALL_TERMS,ranked.match)
            assertFalse(ranked.artistCreditIncomplete)
            assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in ranked.warnings)
            assertFalse(A0CandidateRank.Warning.ARTIST_CONFLICT in ranked.warnings)
        }
    }

    @Test fun exactArtistFolderCanCreditTitleOnlyFileButUnrelatedFoldersCannot() {
        for(folder in listOf("BTS","BTS - Proof","BTS/Proof")) {
            val ranked=rank(name="Boy In Luv.flac",folder=folder)
            assertEquals(folder,A0CandidateRank.Match.ALL_TERMS,ranked.match)
            assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in ranked.warnings)
        }
        for(folder in listOf("Proof","BTS Archive","Other BTS",
            "BTS - Other Album","BTS/Covers/Proof","BTS/Other/Proof")) {
            val ranked=rank(name="Boy In Luv.flac",folder=folder)
            assertNotEquals(folder,A0CandidateRank.Match.ALL_TERMS,ranked.match)
        }
    }

    @Test fun titleOrDecoratedCreditsNeverFabricateAnAlias() {
        for(name in listOf("Boy In Luv BTS.flac",
            "Other BTS - Boy In Luv.flac",
            "BTS Archive - Boy In Luv.flac")) {
            val ranked=rank(name=name)
            assertTrue(name,ranked.match!=A0CandidateRank.Match.ALL_TERMS ||
                A0CandidateRank.Warning.ARTIST_CONFLICT in ranked.warnings ||
                A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in ranked.warnings)
        }
        val live=rank(name="BTS - Boy In Luv (Live).flac")
        assertTrue(A0CandidateRank.Warning.VERSION_MISMATCH in live.warnings)
    }

    @Test fun featuredArtistAndEditionParenthesesAreNotAliases() {
        for(artist in listOf("BTS (feat. Halsey)",
            "BTS (Live)","BTS (2020)","BTS (JIN)")) {
            assertNotEquals(artist,A0CandidateRank.Match.ALL_TERMS,
                rank(artist).match)
            assertNotEquals(artist,
                A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,
                album("BTS - Proof",artist).status)
        }
    }

    @Test fun aliasInExactAlbumDirectoryIsStillOnlyUnverifiedClaim() {
        for(artist in listOf("BTS (방탄소년단)","방탄소년단 (BTS)")) {
            val exact=album("BTS - Proof",artist)
            assertEquals(A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,
                exact.status)
            assertFalse(exact.authorizesTransfer)
            assertEquals(A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,
                album("BTS",artist).status)
            assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,
                album("BTS - Other Album",artist).status)
            assertEquals(A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,
                album("BTS - Proof [FLAC]",artist).status)
            assertNotEquals(A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,
                album("BTS - Proof",artist,
                    source=A0CanonicalAlbumPolicy.ClaimSource.EXPLICIT_ALBUM_FIELD
                ).status)
            assertEquals(A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,
                album("BTS - Proof",artist,
                    mbid="11111111-1111-4111-8111-111111111111").status)
        }
    }

    @Test fun nativeVowelSignsAndMixedArtistNamesDoNotBecomeLooseAliases() {
        assertNull(A0ArtistCreditAliases.latinAlias("BTS (feat. 다른)"))
        assertNull(A0ArtistCreditAliases.latinAlias("BTS (2026)"))
        assertNull(A0ArtistCreditAliases.latinAlias("BTS (Live)"))
        assertNull(A0ArtistCreditAliases.latinAlias("BTS (방탄소년단) feat Halsey"))
        assertNull(A0ArtistCreditAliases.latinAlias("BTS (방탄소년단 / 다른)"))
        assertEquals("BTS",A0ArtistCreditAliases.latinAlias("BTS (방탄소년단)"))
        assertEquals("BTS",A0ArtistCreditAliases.latinAlias("BTS（방탄소년단）"))
        assertEquals("BTS",A0ArtistCreditAliases.latinAlias("방탄소년단（BTS）"))
        assertEquals("BTS",A0ArtistCreditAliases.latinAlias("방탄소년단 (BTS)"))
    }
}
