package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0CandidateRankUnicodeSafetyTest {
    @Test fun devanagariVowelSignsMustNotCollapseDifferentSongTitles() {
        assertNotEquals(A0CandidateRank.tokens("गीत"),
            A0CandidateRank.tokens("गत"))
        val rank=A0CandidateRank.rank(
            searchPhrase="Artist - गीत",name="Artist - गत.flac",
            declaredBitrateKbps=900,remotePath="Shared/Artist/Album/Artist - गत.flac",
            format="flac",declaredDurationSeconds=200,
            expectedDurationSeconds=200,expectedAlbum="Album")
        assertNotEquals(A0CandidateRank.Match.ALL_TERMS,rank.match)
    }

    @Test fun nonLatinCombiningMarksRemainPartOfCanonicalTerms() {
        assertEquals(listOf("गीत"),A0CandidateRank.tokens("गीत"))
        assertEquals(listOf("गत"),A0CandidateRank.tokens("गत"))
        assertEquals(A0CandidateRank.tokens("संगीत"),
            A0CandidateRank.tokens(java.text.Normalizer.normalize(
                "संगीत",java.text.Normalizer.Form.NFD)))
    }

    @Test fun latinAccentFoldingAndComposedHangulStayCompatible() {
        assertEquals(listOf("bjork","joga"),
            A0CandidateRank.tokens("Björk Jóga"))
        assertEquals(A0CandidateRank.tokens("Björk Jóga"),
            A0CandidateRank.tokens("Bjo\u0308rk Jo\u0301ga"))
        assertEquals(listOf("노래"),A0CandidateRank.tokens("노래"))
        assertEquals(A0CandidateRank.tokens("노래"),
            A0CandidateRank.tokens("\u1102\u1169\u1105\u1162"))
    }

    @Test fun editionSpellingAliasesDoNotEraseNonLatinDiacritics() {
        assertEquals(listOf("गीत","demo","version"),
            A0CandidateRank.tokens("गीत (Demo Ver.)"))
        assertNotEquals(A0CandidateRank.tokens("गीत (Demo Ver.)"),
            A0CandidateRank.tokens("गत (Demo Version)"))
    }
}
