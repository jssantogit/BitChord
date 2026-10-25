package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/**
 * Frozen calibration-v3 edge cases: advertised WAV is not proof of PCM.
 * Peer metadata is untrusted. These fixtures are synthetic, not measured
 * accuracy or a license to perform an unattended download.
 */
class A0CandidateCalibrationV3Test {
    private data class Case(
        val id:String,
        val expected:A0CandidatePreflight.Confidence,
        val reason:A0CandidatePreflight.Reason?=null,
        val format:String="wav",
        val bytes:Long,
        val sampleRate:Int?=44_100,
        val bitDepth:Int?=16,
    )
    private val STRONG=A0CandidatePreflight.Confidence.STRONG
    private val REVIEW=A0CandidatePreflight.Confidence.REVIEW
    private val UNSAFE=A0CandidatePreflight.Confidence.UNSAFE
    private val pack=listOf(
        Case("wav-too-small-for-even-half-of-mono-pcm",UNSAFE,
            A0CandidatePreflight.Reason.SIZE_CONFLICT,bytes=2_000_000L),
        Case("wav-plausible-mono",STRONG,bytes=16_000_000L),
        Case("wav-plausible-stereo",STRONG,bytes=29_000_000L),
        Case("flac-is-not-subject-to-pcm-bounds",STRONG,
            format="flac",bytes=4_000_000L),
        Case("wav-without-depth-needs-review",REVIEW,
            A0CandidatePreflight.Reason.QUALITY_UNCONFIRMED,
            bytes=16_000_000L,bitDepth=null)
    )

    private fun assess(c:Case):A0CandidatePreflight.Assessment {
        val file="ILLIT Magnetic."+c.format
        val rank=A0CandidateRank.rank("ILLIT - Magnetic",file,null,
            remotePath="Music/ILLIT/"+file,format=c.format,
            declaredDurationSeconds=161,expectedDurationSeconds=161,
            sizeBytes=c.bytes,declaredSampleRateHz=c.sampleRate,
            declaredBitDepth=c.bitDepth)
        return A0CandidatePreflight.assess("ILLIT - Magnetic",rank,null,
            declaredDurationSeconds=161,expectedDurationSeconds=161,
            format=c.format,sizeBytes=c.bytes,
            declaredSampleRateHz=c.sampleRate,declaredBitDepth=c.bitDepth)
    }

    @Test fun policyAndFrozenPackAreVersioned() {
        assertEquals("preflight-v3",A0CandidatePreflight.POLICY_VERSION)
        assertEquals(5,pack.size)
        assertEquals(5,pack.map { it.id }.distinct().size)
        assertEquals(3,pack.count { it.expected==STRONG })
        assertEquals(1,pack.count { it.expected==REVIEW })
        assertEquals(1,pack.count { it.expected==UNSAFE })
    }

    @Test fun pcmClaimsMustNotProduceFalseStrongResults() {
        val drift=pack.mapNotNull { c ->
            val actual=assess(c)
            if(actual.confidence!=c.expected ||
                (c.reason!=null && c.reason !in actual.reasons))
                c.id+":expected="+c.expected+":got="+actual.confidence
            else null
        }
        assertTrue("Calibration-v3 drift: "+drift,drift.isEmpty())
    }
}
