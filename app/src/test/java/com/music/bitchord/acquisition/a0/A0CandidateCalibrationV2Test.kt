package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/**
 * Frozen edge-case pack complementing calibration-v1. Synthetic data only.
 * Never equate declared file similarity with actual audio identity.
 */
class A0CandidateCalibrationV2Test {
    private data class Case(
        val id:String, val expected:A0CandidatePreflight.Confidence,
        val reason:A0CandidatePreflight.Reason?=null,
        val query:String="ILLIT - Magnetic",
        val file:String="ILLIT Magnetic.mp3",
        val path:String?=null,
        val bitrate:Int?=320,
        val duration:Int?=161,
        val reference:Int?=161,
        val size:Long=6_400_000L,
        val format:String="mp3",
        val sampleRate:Int?=null,
        val depth:Int?=null,
    )
    private val S=A0CandidatePreflight.Confidence.STRONG
    private val R=A0CandidatePreflight.Confidence.REVIEW
    private val U=A0CandidatePreflight.Confidence.UNSAFE
    private val cases=listOf(
        Case("good-studio",S),
        Case("studio-in-live-archive-ancestor",S,
            path="Music/Live Archive/ILLIT/ILLIT Magnetic.mp3"),
        Case("live-in-immediate-release-folder",U,
            A0CandidatePreflight.Reason.VERSION_CONFLICT,file="Magnetic.mp3",
            path="Music/ILLIT/Magnetic Live/Magnetic.mp3"),
        Case("remix-in-immediate-release-folder",U,
            A0CandidatePreflight.Reason.VERSION_CONFLICT,file="Magnetic.mp3",
            path="Music/ILLIT/Remix/Magnetic.mp3"),
        Case("karaoke-in-immediate-release-folder",U,
            A0CandidatePreflight.Reason.VERSION_CONFLICT,file="Magnetic.mp3",
            path="Music/ILLIT/Karaoke/Magnetic.mp3"),
        Case("bitrate-negative",U,A0CandidatePreflight.Reason.ENCODING_CONFLICT,
            bitrate=-1),
        Case("bitrate-zero",U,A0CandidatePreflight.Reason.ENCODING_CONFLICT,
            bitrate=0),
        Case("bitrate-16",U,A0CandidatePreflight.Reason.ENCODING_CONFLICT,
            bitrate=16),
        Case("bitrate-overflow",U,A0CandidatePreflight.Reason.ENCODING_CONFLICT,
            bitrate=2_000_000),
        Case("low-bitrate-plausible",R,A0CandidatePreflight.Reason.QUALITY_UNCONFIRMED,
            bitrate=128,size=2_576_000L),
        Case("duration-unavailable",R,A0CandidatePreflight.Reason.DURATION_UNCONFIRMED,
            reference=null),
        Case("declared-lossless",S,file="ILLIT Magnetic.flac",format="flac",
            bitrate=null,sampleRate=44100,depth=16,size=24_000_000L),
        Case("unverified-lossless",R,A0CandidatePreflight.Reason.QUALITY_UNCONFIRMED,
            file="ILLIT Magnetic.flac",format="flac",bitrate=null,
            sampleRate=8000,depth=8,size=24_000_000L),
        Case("title-only-artist-unconfirmed",R,A0CandidatePreflight.Reason.ARTIST_UNCONFIRMED,
            query="Magnetic",file="Magnetic.mp3"),
        Case("shuffled-multiword-title",R,A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS,
            query="Artist - Save Your Tears",file="Artist Tears Your Save.mp3",
            path="Music/Artist/Artist Tears Your Save.mp3",
            duration=215,reference=215,size=8_600_000L),
        Case("repeated-title-token-missing",R,A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS,
            query="Artist - Do Do Do",file="Artist Do.mp3",
            path="Music/Artist/Artist Do.mp3"),
        Case("correct-multiword-title",S,query="Artist - Save Your Tears",
            file="Artist Save Your Tears.mp3",
            path="Music/Artist/Artist Save Your Tears.mp3",
            duration=215,reference=215,size=8_600_000L)
    )
    private fun assess(c:Case):A0CandidatePreflight.Assessment {
        val rank=A0CandidateRank.rank(c.query,c.file,c.bitrate,
            remotePath=c.path ?: "Music/ILLIT/" + c.file,format=c.format,
            declaredDurationSeconds=c.duration,expectedDurationSeconds=c.reference,
            sizeBytes=c.size,declaredSampleRateHz=c.sampleRate,declaredBitDepth=c.depth)
        return A0CandidatePreflight.assess(c.query,rank,c.bitrate,
            declaredDurationSeconds=c.duration,expectedDurationSeconds=c.reference,
            format=c.format,sizeBytes=c.size,declaredSampleRateHz=c.sampleRate,
            declaredBitDepth=c.depth)
    }

    @Test fun packAndPolicyAreExplicitlyVersioned() {
        // Frozen calibration-v2 data is also run against the current v3 policy.
        assertEquals("preflight-v3",A0CandidatePreflight.POLICY_VERSION)
        assertEquals(17,cases.size)
        assertEquals(cases.size,cases.map { it.id }.distinct().size)
        assertEquals(4,cases.count { it.expected==S })
        assertEquals(6,cases.count { it.expected==R })
        assertEquals(7,cases.count { it.expected==U })
    }

    @Test fun allExpectedClassesAndTypedReasonsHold() {
        val regressions=cases.mapNotNull { c ->
            val result=assess(c)
            if(result.confidence!=c.expected ||
                (c.reason!=null && c.reason !in result.reasons))
                c.id+":expected="+c.expected+":actual="+result.confidence
            else null
        }
        assertTrue("Calibration-v2 drift: $regressions",regressions.isEmpty())
    }

    @Test fun worstCasePromotionRateMustRemainZeroInFakePack() {
        val incorrect=cases.filter { it.expected!=S && assess(it).confidence==S }
        assertEquals("False STRONG: "+incorrect.map { it.id },0,incorrect.size)
    }
}
