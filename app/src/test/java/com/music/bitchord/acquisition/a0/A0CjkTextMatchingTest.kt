package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** CJK text may be orthographically equivalent; translations never are. */
class A0CjkTextMatchingTest {
    @Test fun japaneseHalfwidthKanaAndFullwidthLatinAreCanonicalWidthOnly() {
        assertEquals(A0CandidateRank.tokens("ガールズ"),A0CandidateRank.tokens("ｶﾞｰﾙｽﾞ"))
        assertEquals(A0CandidateRank.tokens("ガ"),A0CandidateRank.tokens("ｶﾞ"))
        assertEquals(A0CandidateRank.tokens("YOASOBI"),A0CandidateRank.tokens("ＹＯＡＳＯＢＩ"))
        assertEquals("YOASOBI ガールズ",A0SearchQuery.normalize("ＹＯＡＳＯＢＩ - ｶﾞｰﾙｽﾞ"))
        assertEquals(A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,
            A0CanonicalAlbumPolicy.assess(
                A0CanonicalAlbumPolicy.Target("ガールズ"),
                A0CanonicalAlbumPolicy.Claim("ｶﾞｰﾙｽﾞ")).status)
    }

    @Test fun koreanDecomposedJamoRemainNfcEquivalent() {
        assertEquals(A0CandidateRank.tokens("노래"),
            A0CandidateRank.tokens("\u1102\u1169\u1105\u1162"))
        assertEquals("BTS 노래",A0SearchQuery.normalize(
            "BTS - \u1102\u1169\u1105\u1162"))
    }

    @Test fun simplifiedTraditionalAndJapaneseCompatibilityKanjiMustNotCollapse() {
        assertNotEquals(A0CandidateRank.tokens("音乐"),A0CandidateRank.tokens("音樂"))
        assertNotEquals(A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,
            A0CanonicalAlbumPolicy.assess(
                A0CanonicalAlbumPolicy.Target("音乐"),
                A0CanonicalAlbumPolicy.Claim("音樂")).status)
        // NFKC on the entire string would silently fold these code points.
        assertNotEquals(A0CandidateRank.tokens("金"),A0CandidateRank.tokens("金"))
        assertNotEquals(A0CandidateRank.tokens("गीत"),A0CandidateRank.tokens("गत"))
    }

    @Test fun canonicalCjkTitleCanHaveExplicitDescriptiveEnglishAlias() {
        for(title in listOf("상남자 (Boy In Luv)",
            "夜に駆ける (Racing Into The Night)",
            "晴天 (Sunny Summer Day)")) {
            assertNotNull(title,A0CjkTitleAliases.latinAlias(title))
        }
        assertEquals("Boy In Luv",A0CjkTitleAliases.latinAlias("상남자 (Boy In Luv)"))
        assertEquals("Boy In Luv",A0CjkTitleAliases.latinAlias("상남자（Boy In Luv）"))
        assertEquals("Racing Into The Night",
            A0CjkTitleAliases.latinAlias("夜に駆ける（Racing Into The Night）"))
        assertEquals("Racing Into The Night",
            A0SearchRecoveryPlan(
                "YOASOBI - 夜に駆ける (Racing Into The Night)").next(
                6000,false,setOf("YOASOBI 夜に駆ける Racing Into The Night")))
    }

    @Test fun genericParenthesesAndEditionDescriptorsAreNeverSongAliases() {
        for(title in listOf("Love (Boy In Luv)","Jóga (Boy In Luv)",
            "상남자 (Live Version)","夜に駆ける (Acoustic Version)",
            "晴天 (2021 Remaster)","晴天 (Demo Version)",
            "晴天 (Live at Seoul)","晴天 (Other Artist Remix)",
            "晴天 (Love)","晴天 (BTS feat Halsey)",
            "晴天 (Boy In Luv) - Remastered",
            "晴天 feat Halsey (Sunny Summer Day)",
            "晴天 - Live (Sunny Summer Day)",
            "晴天 Remix (Sunny Summer Day)")) {
            assertNull(title,A0CjkTitleAliases.latinAlias(title))
        }
    }

    @Test fun bilingualAsianTitleCanMatchExactExplicitAlternativeOnly() {
        fun rank(query:String,name:String,folder:String="Proof")=
            A0CandidateRank.rank(query,name,900,
                remotePath="Shared/$folder/$name",format="flac",
                declaredDurationSeconds=240,expectedDurationSeconds=240,
                expectedAlbum=folder,sizeBytes=29_000_000)
        for((artist,title,english) in listOf(
            Triple("BTS","상남자 (Boy In Luv)","Boy In Luv"),
            Triple("YOASOBI","夜に駆ける (Racing Into The Night)","Racing Into The Night"),
            Triple("周杰伦","晴天 (Sunny Summer Day)","Sunny Summer Day"))) {
            val result=rank("$artist - $title","$artist - $english.flac")
            assertEquals(artist,A0CandidateRank.Match.ALL_TERMS,result.match)
            assertFalse(result.titleTermsIncomplete)
            assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in result.warnings)
            assertFalse(A0CandidateRank.Warning.ARTIST_CONFLICT in result.warnings)
        }
        for(name in listOf("BTS - Boy In Luv (Live).flac",
            "BTS - Boy In Luv Acoustic.flac",
            "Other BTS - Boy In Luv.flac",
            "BTS - Completely Different.flac",
            "Boy In Luv BTS.flac")) {
            val result=rank("BTS - 상남자 (Boy In Luv)",name)
            assertTrue(name,result.match!=A0CandidateRank.Match.ALL_TERMS ||
                A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in result.warnings ||
                A0CandidateRank.Warning.VERSION_MISMATCH in result.warnings ||
                A0CandidateRank.Warning.ARTIST_CONFLICT in result.warnings)
        }
        // No artist -> not auto-eligible, despite an exact title alias.
        assertNotEquals(A0CandidateRank.Match.ALL_TERMS,rank(
            "BTS - 상남자 (Boy In Luv)","Boy In Luv.flac").match)
    }

    @Test fun releaseShadowInitialScreenAndRankUseSameBilingualAlternatives() {
        val target="BTS - 상남자 (Boy In Luv)"
        assertTrue(A0ReleaseDiscoveryShadowAdapter.fileMatchesTitle(
            target,"BTS - Boy In Luv.flac"))
        assertTrue(A0ReleaseDiscoveryShadowAdapter.fileMatchesTitle(
            target,"BTS - 상남자 (Boy In Luv).flac"))
        assertFalse(A0ReleaseDiscoveryShadowAdapter.fileMatchesTitle(
            target,"BTS - Some Other Song.flac"))
        assertFalse(A0ReleaseDiscoveryShadowAdapter.fileMatchesTitle(
            "BTS - 밤 (Live Version)","BTS - Live Version.flac"))
        assertEquals(listOf(setOf("상남자","boy","in","luv"),setOf("boy","in","luv")),
            A0ReleaseDiscoveryShadowAdapter.titleOptions(target))
    }

    @Test fun realReleaseCollectorPreservesCjkTitleUsingExactCanonicalAlias() {
        val target="BTS - 상남자 (Boy In Luv)"
        val collector=A0ObservedCandidateHandoff(
            java.net.Socket(),"local",81,true,searchPhrase=target,
            requestedAlbum="Proof",collectReleaseShadow=true,
            collectManualCandidates=false,releaseQueries=listOf(target)
        )
        val response=A0SoulseekSearchResponse(
            "remote",81,listOf(A0SoulseekSearchFile(
                "Shared\\Proof\\BTS - Boy In Luv.flac",
                29_000_000L,"flac",mapOf(1 to 240,4 to 44100,5 to 16)
            )),emptyList(),true,128_000,0
        )
        assertTrue(collector.offerParsed(response,"remote"))
        assertEquals(1,collector.ticketScreening(81).titleMatched)
        assertEquals(1,collector.ticketScreening(81).shadowInserted)
        val choices=collector.releaseShadowChoices()
        assertEquals(1,choices.size)
        assertEquals(A0CandidateRank.Match.ALL_TERMS,choices.single().matchSignal)
    }
}
