package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/**
 * Frozen identity-multiplicity baseline. Synthetic peer declarations only:
 * artist evidence must not be borrowed from the requested song title.
 */
class A0CandidateCalibrationV4Test {
    private data class Case(
        val id:String,
        val expected:A0CandidatePreflight.Confidence,
        val query:String="ILLIT - Magnetic",
        val file:String="ILLIT Magnetic.mp3",
        val folder:String="Music/ILLIT",
        val reason:A0CandidatePreflight.Reason?=null,
    )
    private val S=A0CandidatePreflight.Confidence.STRONG
    private val R=A0CandidatePreflight.Confidence.REVIEW
    private val pack=listOf(
        Case("duplicate-title",R,file="ILLIT Magnetic Magnetic.mp3",
            reason=A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS),
        Case("duplicate-artist",R,file="ILLIT ILLIT Magnetic.mp3",
            reason=A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS),
        Case("trailing-duplicate-credit",R,file="ILLIT Magnetic ILLIT.mp3",
            reason=A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS),
        Case("artist-in-title-only",R,query="Queen - Queen Of Hearts",
            file="Queen Of Hearts.mp3",folder="Music/Other",
            reason=A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS),
        Case("title-folder-is-not-credit",R,query="Queen - Queen Of Hearts",
            file="Queen Of Hearts.mp3",folder="Music/Queen Of Hearts",
            reason=A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS),
        Case("artist-in-independent-folder",S,query="Queen - Queen Of Hearts",
            file="Queen Of Hearts.mp3",folder="Music/Queen"),
        Case("artist-explicit-in-filename",S,query="Queen - Queen Of Hearts",
            file="Queen - Queen Of Hearts.mp3",folder="Music/Other"),
        Case("ordinary-artist-folder",S,
            file="Magnetic.mp3",folder="Music/ILLIT"),
        Case("valid-repeated-title",S,query="Artist - Do Do Do",
            file="Artist Do Do Do.mp3",folder="Music/Artist"),
        Case("surplus-repeated-title",R,query="Artist - Do Do Do",
            file="Artist Do Do Do Do.mp3",folder="Music/Artist",
            reason=A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS)
    )

    private fun assess(c:Case):A0CandidatePreflight.Assessment {
        val rank=A0CandidateRank.rank(c.query,c.file,320,
            remotePath=c.folder+"/"+c.file,format="mp3",
            declaredDurationSeconds=161,expectedDurationSeconds=161,
            sizeBytes=6_400_000L)
        return A0CandidatePreflight.assess(c.query,rank,320,
            declaredDurationSeconds=161,expectedDurationSeconds=161,
            format="mp3",sizeBytes=6_400_000L)
    }

    @Test fun policyAndFixturesHaveAnExplicitVersion() {
        assertEquals("preflight-v4",A0CandidatePreflight.POLICY_VERSION)
        assertEquals(10,pack.size)
        assertEquals(10,pack.map { it.id }.distinct().size)
        assertEquals(4,pack.count { it.expected==S })
        assertEquals(6,pack.count { it.expected==R })
    }

    @Test fun reviewedLabelsAndReasonsArePreservedWithoutFalseStrong() {
        val drift=pack.mapNotNull { c ->
            val a=assess(c)
            if(a.confidence!=c.expected || (c.reason!=null && c.reason !in a.reasons))
                c.id+":expected="+c.expected+":observed="+a.confidence
            else null
        }
        assertTrue("Calibration-v4 drift: "+drift,drift.isEmpty())
    }
}
