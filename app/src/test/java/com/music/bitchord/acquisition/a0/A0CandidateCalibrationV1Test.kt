package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/**
 * Versioned fake-only MusicLab-style calibration pack.
 * No sockets, downloads, library operations, peer identifiers or private data.
 *
 * This is REGRESSION coverage, not a statistical claim of real-world accuracy.
 * Update pack version and review expected labels explicitly when policy changes.
 */
class A0CandidateCalibrationV1Test {
    private val version=A0CandidatePreflight.POLICY_VERSION
    private val STRONG=A0CandidatePreflight.Confidence.STRONG
    private val REVIEW=A0CandidatePreflight.Confidence.REVIEW
    private val UNSAFE=A0CandidatePreflight.Confidence.UNSAFE

    private data class Case(
        val id:String,
        val expected:A0CandidatePreflight.Confidence,
        val query:String="ILLIT - Magnetic",
        val filename:String="ILLIT Magnetic.mp3",
        val format:String="mp3",
        val bitrate:Int?=320,
        val duration:Int?=161,
        val referenceSeconds:Int?=161,
        val bytes:Long=6_400_000L,
        val path:String?=null,
        val sampleRate:Int?=null,
        val depth:Int?=null,
        val reason:A0CandidatePreflight.Reason?=null
    )
    private val studio=Case("studio",STRONG)
    private val pack=listOf(
        studio,
        Case("artist-title-exact",STRONG,filename="ILLIT - Magnetic.mp3"),
        Case("title-in-artist-folder",STRONG,filename="Magnetic.mp3",
            path="Music/ILLIT/Magnetic.mp3"),
        Case("numbered-track",STRONG,filename="01 - Magnetic.mp3",
            path="Music/ILLIT/01 - Magnetic.mp3"),
        Case("official-audio",STRONG,filename="ILLIT Magnetic (Official Audio).mp3"),
        Case("bitrate-label",STRONG,filename="ILLIT Magnetic 320 kbps.mp3"),
        Case("official-hq",STRONG,filename="ILLIT Magnetic HQ.mp3"),
        Case("flac-16-44100",STRONG,filename="ILLIT Magnetic.flac",
            format="flac",bitrate=null,sampleRate=44100,depth=16,bytes=24_000_000),
        Case("wav-24-48000",STRONG,filename="ILLIT Magnetic.wav",
            format="wav",bitrate=null,sampleRate=48000,depth=24,bytes=28_000_000),
        Case("unicode-diacritics",STRONG,query="Björk - Jóga",
            filename="Bjork Joga.mp3",path="Music/Bjork/Bjork Joga.mp3"),
        Case("alive-not-live",STRONG,query="Artist - Alive",
            filename="Artist Alive.mp3",path="Music/Artist/Artist Alive.mp3"),
        Case("premix-not-remix",STRONG,query="Artist - Premix",
            filename="Artist Premix.mp3",path="Music/Artist/Artist Premix.mp3"),
        Case("delivery-not-live",STRONG,query="Artist - Delivery",
            filename="Artist Delivery.mp3",path="Music/Artist/Artist Delivery.mp3"),
        Case("requested-live",STRONG,query="ILLIT - Magnetic live",
            filename="ILLIT Magnetic live.mp3"),
        Case("requested-remix",STRONG,query="ILLIT - Magnetic remix",
            filename="ILLIT Magnetic remix.mp3"),
        Case("duration-boundary-8",STRONG,duration=169,bytes=6_760_000L),

        Case("artist-unconfirmed",REVIEW,query="Magnetic",filename="Magnetic.mp3",
            reason=A0CandidatePreflight.Reason.ARTIST_UNCONFIRMED),
        Case("duration-unreported",REVIEW,duration=null,
            reason=A0CandidatePreflight.Reason.DURATION_UNCONFIRMED),
        Case("catalog-duration-missing",REVIEW,referenceSeconds=null,
            reason=A0CandidatePreflight.Reason.DURATION_UNCONFIRMED),
        Case("mp3-128",REVIEW,bitrate=128,bytes=2_576_000L,
            reason=A0CandidatePreflight.Reason.QUALITY_UNCONFIRMED),
        Case("mp3-192",REVIEW,bitrate=192,bytes=3_864_000L,
            reason=A0CandidatePreflight.Reason.QUALITY_UNCONFIRMED),
        Case("bitrate-unreported",REVIEW,bitrate=null,
            reason=A0CandidatePreflight.Reason.QUALITY_UNCONFIRMED),
        Case("uncredited-filename-artist",REVIEW,filename="Other Artist Magnetic.mp3",
            reason=A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS),
        Case("unexplained-album-version",REVIEW,filename="ILLIT Magnetic Album Version.mp3",
            reason=A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS),
        Case("lossless-8bit",REVIEW,filename="ILLIT Magnetic.flac",format="flac",
            bitrate=null,sampleRate=44100,depth=8,bytes=24_000_000L,
            reason=A0CandidatePreflight.Reason.QUALITY_UNCONFIRMED),
        Case("lossless-unverified",REVIEW,filename="ILLIT Magnetic.flac",
            format="flac",bitrate=null,bytes=24_000_000L,
            reason=A0CandidatePreflight.Reason.QUALITY_UNCONFIRMED),

        Case("explicit-other-artist",UNSAFE,filename="Other Artist - Magnetic.mp3",
            reason=A0CandidatePreflight.Reason.ARTIST_CONFLICT),
        Case("unrequested-live",UNSAFE,filename="ILLIT Magnetic live.mp3",
            reason=A0CandidatePreflight.Reason.VERSION_CONFLICT),
        Case("unrequested-remix",UNSAFE,filename="ILLIT Magnetic remix.mp3",
            reason=A0CandidatePreflight.Reason.VERSION_CONFLICT),
        Case("wrong-duration",UNSAFE,duration=250,
            reason=A0CandidatePreflight.Reason.DURATION_CONFLICT),
        Case("duration-boundary-9",UNSAFE,duration=170,
            reason=A0CandidatePreflight.Reason.DURATION_CONFLICT),
        Case("impossible-mp3-rate",UNSAFE,bitrate=1000,
            reason=A0CandidatePreflight.Reason.ENCODING_CONFLICT),
        Case("impossible-mp3-bytes",UNSAFE,bytes=100_000L,
            reason=A0CandidatePreflight.Reason.SIZE_CONFLICT),
        Case("wrong-song",UNSAFE,filename="Completely Different.mp3",
            reason=A0CandidatePreflight.Reason.TITLE_MISMATCH),
        Case("artist-spread-across-folders",UNSAFE,query="New Jeans - OMG",
            filename="OMG.mp3",path="Music/New/Jeans/OMG.mp3",
            reason=A0CandidatePreflight.Reason.TITLE_MISMATCH),
        Case("title-in-wrong-artist-folder",UNSAFE,filename="Magnetic.mp3",
            path="Music/Other/Magnetic.mp3",
            reason=A0CandidatePreflight.Reason.TITLE_MISMATCH)
    )

    private fun evaluate(c:Case):A0CandidatePreflight.Assessment {
        val path=c.path ?: "Music/ILLIT/" + c.filename
        val ranking=A0CandidateRank.rank(c.query,c.filename,c.bitrate,
            remotePath=path,format=c.format,declaredDurationSeconds=c.duration,
            expectedDurationSeconds=c.referenceSeconds,declaredSampleRateHz=c.sampleRate,
            declaredBitDepth=c.depth,sizeBytes=c.bytes)
        return A0CandidatePreflight.assess(c.query,ranking,c.bitrate,
            declaredDurationSeconds=c.duration,expectedDurationSeconds=c.referenceSeconds,
            format=c.format,sizeBytes=c.bytes,declaredSampleRateHz=c.sampleRate,
            declaredBitDepth=c.depth)
    }

    @Test fun packIdentityIsStableAndHasAllThreeRiskCategories() {
        assertEquals("preflight-v1",version)
        assertEquals(36,pack.size)
        assertEquals(pack.size,pack.map { it.id }.distinct().size)
        assertEquals(16,pack.count { it.expected==STRONG })
        assertEquals(10,pack.count { it.expected==REVIEW })
        assertEquals(10,pack.count { it.expected==UNSAFE })
    }

    @Test fun everyCaseMustMatchItsReviewedClassificationAndTypedReason() {
        val failures=pack.mapNotNull { c ->
            val a=evaluate(c)
            if(a.confidence!=c.expected || (c.reason!=null && c.reason !in a.reasons))
                c.id+":"+a.confidence.name
            else null
        }
        assertTrue("Calibration regressions, review pack labels: "+failures,failures.isEmpty())
    }

    @Test fun noKnownAmbiguousOrBadFixtureIsMarkedStrong() {
        val unsafePromotions=pack.filter { it.expected!=STRONG }
            .filter { evaluate(it).confidence==STRONG }
        assertTrue("False STRONG fixture IDs: "+unsafePromotions.map { it.id },
            unsafePromotions.isEmpty())
    }

    @Test fun counterfactualSimulationNeverTriggersAConnectionOrTransfer() {
        val scored=pack.map { c ->
            val a=evaluate(c)
            A0CandidatePreflight.Weighted(a,if(a.confidence==STRONG) 85 else 95)
        }
        assertEquals(A0CandidatePreflight.SimulationStatus.AMBIGUOUS,
            A0CandidatePreflight.simulate(scored).status)
        assertEquals(A0CandidatePreflight.SimulationStatus.NO_STRONG,
            A0CandidatePreflight.simulate(scored.drop(16)).status)
    }
}
