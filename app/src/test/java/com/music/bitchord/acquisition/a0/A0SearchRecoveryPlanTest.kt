package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0SearchRecoveryPlanTest {
    @Test fun accentedArtistUsesAsciiBeforeBoundedTitleOnlyRecovery() {
        val recovery=A0SearchRecoveryPlan("Björk - Jóga")
        val already=mutableSetOf("Björk Jóga")
        assertNull(recovery.next(1000,hasRelevantCandidate=false,alreadySent=already))
        val first=recovery.next(6000,false,already)
        assertEquals("Bjork Joga",first)
        already.add(checkNotNull(first))
        assertEquals("Jóga",recovery.next(12000,false,already))
        already.add("Jóga")
        assertNull(recovery.next(15000,false,already))
    }
    @Test fun longerDescriptiveTitlePrecedesCompilationAlbumVariant() {
        val original="BTS (방탄소년단) - Boy In Luv"
        val recovery=A0SearchRecoveryPlan(original,album="Proof")
        val already=mutableSetOf(A0SearchQuery.normalize(original))
        assertEquals("Boy In Luv",recovery.next(6000,false,already))
        already.add("Boy In Luv")
        assertEquals(A0SearchQuery.normalize(
            "BTS (방탄소년단) Proof Boy In Luv"),recovery.next(12000,false,already))
    }
    @Test fun releaseWideFallbackSearchesAlbumToRecoverOtherTracksWithoutTransfer() {
        val recovery=A0SearchRecoveryPlan(
            "BTS (방탄소년단) - Boy In Luv",album="Proof",releaseWide=true)
        val sent=mutableSetOf(A0SearchQuery.normalize(
            "BTS (방탄소년단) - Boy In Luv"))
        assertEquals("BTS Proof",recovery.next(6000,false,sent))
        sent.add("BTS Proof")
        assertEquals("Boy In Luv",recovery.next(12000,false,sent))
        assertFalse(recovery.authorizesTransfer)
        val generic=A0SearchRecoveryPlan("BTS - Normal","ARIRANG",releaseWide=true)
        assertEquals("BTS ARIRANG",generic.next(6000,false,setOf("BTS Normal")))
        val noAlbum=A0SearchRecoveryPlan("BTS - Normal","",releaseWide=true)
        assertEquals("Normal",noAlbum.next(6000,false,setOf("BTS Normal")))
    }

    @Test fun globallySearchedAlbumDoesNotConsumeAQuerySlotOrHideSpecificFallback() {
        val plan=A0SearchRecoveryPlan("BTS - Normal","ARIRANG",releaseWide=true)
        val sent=mutableSetOf("BTS Normal")
        val albumAlreadySearched=setOf("BTS ARIRANG")
        assertEquals("BTS ARIRANG Normal",plan.next(
            6000,false,sent,excludedQueries=albumAlreadySearched))
        sent.add("BTS ARIRANG Normal")
        assertEquals("Normal",plan.next(
            12000,false,sent,excludedQueries=albumAlreadySearched))
        sent.add("Normal")
        assertNull(plan.next(14000,false,sent,excludedQueries=albumAlreadySearched))
    }

    @Test fun descriptiveTitleCanStillUseSpecificAlbumRecoveryAfterGlobalDedup() {
        val title="BTS (방탄소년단) - Boy In Luv"
        val plan=A0SearchRecoveryPlan(title,"Proof",releaseWide=true)
        val sent=mutableSetOf(A0SearchQuery.normalize(title))
        val global=setOf("BTS Proof")
        assertEquals("Boy In Luv",plan.next(
            6000,false,sent,excludedQueries=global))
        sent.add("Boy In Luv")
        assertEquals(A0SearchQuery.normalize("BTS (방탄소년단) Proof Boy In Luv"),
            plan.next(12000,false,sent,excludedQueries=global))
        assertNull(plan.next(14000,true,setOf(A0SearchQuery.normalize(title)),
            missingSiblings=true,excludedQueries=global))
    }

    @Test fun repeatedAlbumQueryCanBeSkippedWithoutLosingTheTitleFallback() {
        val original="BTS (방탄소년단) - Boy In Luv"
        val recovery=A0SearchRecoveryPlan(original,"Proof",releaseWide=true)
        val sent=mutableSetOf(A0SearchQuery.normalize(original))
        assertEquals("BTS Proof",recovery.releaseWideQuery)
        sent.add(checkNotNull(recovery.releaseWideQuery)) // already sought by earlier slot
        assertEquals("Boy In Luv",recovery.next(6000,false,sent))
        assertEquals(2,sent.size)
    }
    @Test fun bilingualKoreanTitleCanSearchItsLatinAliasWithoutChangingIdentity() {
        val title="BTS (방탄소년단) - 상남자 (Boy In Luv)"
        val release=A0SearchRecoveryPlan(title,"Proof",releaseWide=true)
        val sent=mutableSetOf(A0SearchQuery.normalize(title))
        assertEquals("BTS Proof",release.next(6000,false,sent))
        sent.add("BTS Proof")
        assertEquals("Boy In Luv",release.next(12000,false,sent))
        assertFalse(release.authorizesTransfer)
        val standalone=A0SearchRecoveryPlan(title)
        assertEquals("Boy In Luv",standalone.next(6000,false,
            setOf(A0SearchQuery.normalize(title))))
    }
    @Test fun liveOrRemixVersionTagNeverBecomesAFalseLatinAlias() {
        val original="BTS - 노래 (Live)"
        val plan=A0SearchRecoveryPlan(original)
        assertEquals(A0SearchQuery.normalize("노래 (Live)"),
            plan.next(6000,false,setOf(A0SearchQuery.normalize(original))))
    }

    @Test fun multiwordEditionMetadataNeverReplacesActualKoreanTitle() {
        for (suffix in listOf("Live Version","Live at Seoul",
            "Remastered 2023","2023 Remaster","Radio Edit",
            "Extended Mix","Original Mix","Acoustic Version",
            "Demo Version","Dance Remix")) {
            val canonical="BTS - 노래 ($suffix)"
            val expected=A0SearchQuery.normalize("노래 ($suffix)")
            val plan=A0SearchRecoveryPlan(canonical)
            assertEquals("Wrong fallback for edition label",expected,
                plan.next(6000,false,setOf(A0SearchQuery.normalize(canonical))))
        }
        // A genuine Latin name beginning with "Live" must still be used.
        val alias="BTS - 사랑 (Live It Up)"
        assertEquals("Live It Up",A0SearchRecoveryPlan(alias).next(
            6000,false,setOf(A0SearchQuery.normalize(alias))))
    }

    @Test fun shortKoreanTitleStillSupportsAlbumSpecificSiblingDiscovery() {
        val original="BTS - 노래"
        assertNull(A0SearchQuery.titleOnlyFallback(original))
        val plan=A0SearchRecoveryPlan(original,"화양연화",releaseWide=true)
        val sent=mutableSetOf(A0SearchQuery.normalize(original))
        assertEquals("BTS 화양연화",plan.releaseWideQuery)
        assertEquals("BTS 화양연화",plan.next(6000,false,sent))
        sent.add("BTS 화양연화")
        assertEquals("BTS 화양연화 노래",plan.next(12000,false,sent))
        val standalone=A0SearchRecoveryPlan(original)
        assertNull(standalone.next(6000,false,setOf("BTS 노래")))
        // Artist+album+title is specific enough; title-only "Go" is not.
        assertEquals("Artist Album Go",
            A0SearchRecoveryPlan("Artist - Go","Album")
                .next(6000,false,setOf("Artist Go")))
    }

    @Test fun malformedCanonicalTitleCannotBootstrapAnAlbumWideSearch() {
        val plan=A0SearchRecoveryPlan("BTS - ---","화양연화",releaseWide=true)
        assertNull(plan.releaseWideQuery)
        assertNull(plan.next(6000,false,setOf("BTS")))
        assertNull(A0SearchRecoveryPlan("BTS - 노래",album="",
            releaseWide=true).releaseWideQuery)
    }

    @Test fun explicitDemoVersionSearchIsPreferredOverGenericTitleFallback() {
        val original="BTS - Epiphany (Jin Demo Ver.)"
        val plan=A0SearchRecoveryPlan(original,"Proof",releaseWide=true)
        val album=checkNotNull(plan.releaseWideQuery)
        assertEquals("BTS Proof",album)
        assertEquals("BTS Epiphany Jin Demo Version",
            plan.next(6000,false,setOf(album)))
        // A successful album-wide result still avoids redundant variants.
        assertNull(plan.next(6000,true,setOf(album),missingSiblings=false))
        assertEquals("BTS Epiphany Jin Demo Version",
            A0SearchRecoveryPlan(original).next(6000,false,
                setOf("BTS Epiphany Jin Demo Ver")))
    }

    @Test fun releaseSearchCannotDegradeIntoArtistOnlyWhenAlbumIsUnspecified() {
        val track="BTS (방탄소년단) - 상남자 (Boy In Luv)"
        val empty=A0SearchRecoveryPlan(track,album="",releaseWide=true)
        assertNull(empty.releaseWideQuery)
        assertEquals("Boy In Luv",empty.next(6000,false,
            setOf(A0SearchQuery.normalize(track))))
        val invalid=A0SearchRecoveryPlan("BTS - Normal",album="..",releaseWide=true)
        assertNull(invalid.releaseWideQuery)
        val giant=A0SearchRecoveryPlan("BTS - Normal",album="X".repeat(140),
            releaseWide=true)
        assertNull(giant.releaseWideQuery)
    }

    @Test fun LatinAliasIsActuallyPrioritizedInTwoWireFallbackSlots() {
        val title="BTS (방탄소년단) - 상남자 (Boy In Luv)"
        val plan=A0SearchRecoveryPlan(title,album="Proof",releaseWide=true)
        val used=mutableSetOf(A0SearchQuery.normalize(title))
        val first=checkNotNull(plan.next(6000,false,used))
        assertEquals("BTS Proof",first)
        used.add(first)
        assertEquals("Boy In Luv",plan.next(12000,false,used))
        val solo=A0SearchRecoveryPlan(title)
        assertEquals("Boy In Luv",solo.next(6000,false,
            setOf(A0SearchQuery.normalize(title))))
    }

    @Test fun firstTrackAlreadyEligibleStillPermitsOneAlbumSearchForMissingSiblings() {
        val plan=A0SearchRecoveryPlan("ILLIT - Magnetic","SUPER REAL ME",
            releaseWide=true)
        val sent=mutableSetOf("ILLIT Magnetic")
        assertEquals("ILLIT SUPER REAL ME",plan.next(
            6000,true,sent,missingSiblings=true))
        sent.add("ILLIT SUPER REAL ME")
        assertNull(plan.next(12000,true,sent,missingSiblings=true))
        assertNull(plan.next(6000,true,setOf("ILLIT Magnetic"),
            missingSiblings=false))
        assertFalse(plan.authorizesTransfer)
    }

    @Test fun genericTrackReceivesAlbumSpecificRecoveryBeforeBroadTitleOnly() {
        val recovery=A0SearchRecoveryPlan("BTS - Normal",album="ARIRANG")
        val already=mutableSetOf("BTS Normal")
        assertNull(recovery.next(1000,false,already))
        val first=recovery.next(6000,false,already)
        assertEquals("BTS ARIRANG Normal",first)
        already.add(checkNotNull(first))
        assertEquals("Normal",recovery.next(12000,false,already))
        assertNull(recovery.next(12000,true,already))
        assertFalse(recovery.authorizesTransfer)
    }

    @Test fun invalidAlbumNeverProducesUnboundedOrCrossReleaseSearch() {
        val original="ILLIT - More"
        for(album in listOf("", " ", "../", "X".repeat(140), "MoRE")) {
            val next=A0SearchRecoveryPlan(original,album=album)
                .next(6000,false,setOf("ILLIT More"))
            assertEquals("More",next)
        }
    }

    @Test fun ordinarySongGetsOneAdditionalSearchNotRepeatedFlood() {
        val recovery=A0SearchRecoveryPlan("ILLIT - Magnetic")
        val sent=mutableSetOf("ILLIT Magnetic")
        assertEquals("Magnetic",recovery.next(6000,false,sent))
        sent.add("Magnetic")
        assertNull(recovery.next(12000,false,sent))
        assertNull(recovery.next(6000,true,mutableSetOf("ILLIT Magnetic")))
    }
    @Test fun recoveryRespectsOriginalClockAndInvalidShortVariant() {
        val recovery=A0SearchRecoveryPlan("Artist - Go")
        assertNull(recovery.next(6000,false,setOf("Artist Go")))
        assertNull(A0SearchRecoveryPlan("ILLIT - Magnetic").next(
            17000,false,setOf("ILLIT Magnetic")))
        assertFalse(recovery.authorizesTransfer)
    }
    @Test fun noUnexpectedVariationOnUnstructuredOrNoLongerRelevantQuery() {
        val unstructured=A0SearchRecoveryPlan("magnetic")
        assertNull(unstructured.next(6000,false,setOf("magnetic")))
        assertNull(A0SearchRecoveryPlan("Bjork - Joga").next(
            6000,true,setOf("Bjork Joga")))
        assertFalse(unstructured.toString().contains("magnetic"))
    }
}
