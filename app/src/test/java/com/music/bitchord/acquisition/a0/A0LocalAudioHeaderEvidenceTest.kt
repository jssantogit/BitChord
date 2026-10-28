package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0LocalAudioHeaderEvidenceTest {
    @Test fun flacStreamInfoUsesActualLocalSampleRateAndDepth() {
        val bytes=ByteArray(42)
        "fLaC".toByteArray().copyInto(bytes)
        bytes[7]=34 // STREAMINFO payload length
        val rate=96000
        val depth=24
        bytes[18]=(rate ushr 12).toByte()
        bytes[19]=(rate ushr 4).toByte()
        bytes[20]=(((rate and 0xf) shl 4) or ((2-1) shl 1)
            or ((depth-1) ushr 4)).toByte()
        bytes[21]=(((depth-1) and 0xf) shl 4).toByte()
        val parsed=A0LocalAudioHeaderEvidence.inspect(bytes)!!
        assertEquals(A0CanonicalAcquisitionIntent.Codec.FLAC,parsed.codec)
        assertEquals(96000,parsed.sampleRateHz)
        assertEquals(24,parsed.bitDepth)
        assertNull(A0LocalAudioHeaderEvidence.inspect(
            bytes.copyOf().also {it[7]=33}))
    }

    @Test fun wavPcmParsedFromFmtChunkNotFilenameOrClaim() {
        val data=ByteArray(44)
        "RIFF".toByteArray().copyInto(data,0)
        "WAVE".toByteArray().copyInto(data,8)
        "fmt ".toByteArray().copyInto(data,12)
        data[16]=16 // fmt chunk size
        data[20]=1 // PCM
        data[22]=2 // stereo
        data[24]=0x80.toByte();data[25]=0xbb.toByte() // 48k
        data[34]=24 // bit depth
        "data".toByteArray().copyInto(data,36)
        val info=A0LocalAudioHeaderEvidence.inspect(data)!!
        assertEquals(A0CanonicalAcquisitionIntent.Codec.PCM_WAV,info.codec)
        assertEquals(48000,info.sampleRateHz)
        assertEquals(24,info.bitDepth)
        data[20]=3 // float needs distinct codec
        assertNull(A0LocalAudioHeaderEvidence.inspect(data))
    }

    @Test fun malformedOrDisguisedAudioNeverInheritsPeerClaimConfidence() {
        assertNull(A0LocalAudioHeaderEvidence.inspect(ByteArray(100)))
        val bogus=ByteArray(42)
        "fLaC".toByteArray().copyInto(bogus)
        bogus[7]=34
        assertNull(A0LocalAudioHeaderEvidence.inspect(bogus))
        val id3=ByteArray(18)
        "ID3".toByteArray().copyInto(id3)
        assertEquals(A0CanonicalAcquisitionIntent.Codec.MP3,
            A0LocalAudioHeaderEvidence.inspect(id3)?.codec)
    }
}
