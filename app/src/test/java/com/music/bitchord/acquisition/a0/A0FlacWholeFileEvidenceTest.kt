package com.music.bitchord.acquisition.a0

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class A0FlacWholeFileEvidenceTest {
    private fun bytes(hex:String)=hex.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
    // Three CRC-checked 8kHz FLAC frames, produced with FFmpeg, last frame
    // shorter than the usual 192 PCM samples.
    private val frames=listOf(
        "fff81408002b000000e95b",
        "fff81408012c0000008523",
        "fff8640802af8d000000f19b"
    ).map(::bytes)
    private fun stream(vararg frames:ByteArray, total:Long=560):ByteArray {
        val info=ByteArray(42)
        "fLaC".toByteArray().copyInto(info)
        info[4]=0x80.toByte() // last metadata block = STREAMINFO
        info[7]=34
        info[8]=0;info[9]=192.toByte()
        info[10]=0;info[11]=192.toByte()
        val rate=8000
        info[18]=(rate ushr 12).toByte()
        info[19]=(rate ushr 4).toByte()
        info[20]=((rate and 15) shl 4).toByte()
        info[21]=((15 shl 4) or (total ushr 32).toInt()).toByte()
        info[22]=(total ushr 24).toByte()
        info[23]=(total ushr 16).toByte()
        info[24]=(total ushr 8).toByte()
        info[25]=total.toByte()
        return info + frames.fold(ByteArray(0)) { acc, frame -> acc+frame }
    }
    private fun check(bytes:ByteArray,cancel:()->Boolean={false}):
        A0FlacWholeFileEvidence.Result {
        val file=File.createTempFile("flac-independent-", ".flac")
        return try {
            file.writeBytes(bytes)
            A0FlacWholeFileEvidence.inspect(file,192,560,8000,cancel)
        } finally { file.delete() }
    }
    @Test fun fullLocalFileFramesAreCheckedIndependentOfAndroidPackets() {
        val r=check(stream(*frames.toTypedArray()))
        assertEquals(A0FlacWholeFileEvidence.Status.VERIFIED,r.status)
        assertEquals(3,r.frames)
        assertEquals(560L,r.pcmSamples)
    }
    @Test fun embeddedChecksumValidSyncInAudioPayloadIsNotARealFrameBoundary() {
        // The first CRC-valid encoded frame contains a header-shaped byte
        // pattern with a genuine header CRC8 inside its payload. The
        // outer frame CRC16 remains valid; only a frame-boundary CRC16==0
        // may turn a header candidate into a new frame.
        val withEmbeddedHeader=bytes(
            "fff81408002b00fff81408012c0000d228")
        val result=check(stream(withEmbeddedHeader,frames[1],frames[2]))
        assertEquals(A0FlacWholeFileEvidence.Status.VERIFIED,result.status)
        assertEquals(3,result.frames)
        assertEquals(560L,result.pcmSamples)
        assertEquals(1,result.embeddedHeaderCandidates)
        assertEquals(4,result.headerCandidates)
    }

    @Test fun corruptedEncodedFrameWithEmbeddedHeaderStillFailsClosed() {
        val embedded=bytes("fff81408002b00fff81408012c0000d228")
        embedded[embedded.size-1]=(embedded.last().toInt() xor 1).toByte()
        val result=check(stream(embedded,frames[1],frames[2]))
        assertNotEquals(A0FlacWholeFileEvidence.Status.VERIFIED,result.status)
    }

    @Test fun truncatedFileAndAlteredFrameDataNeverApprove() {
        val valid=stream(*frames.toTypedArray())
        assertNotEquals(A0FlacWholeFileEvidence.Status.VERIFIED,
            check(valid.copyOf(valid.size-1)).status)
        val broken=valid.clone()
        broken[42+frames[0].size+7]=(broken[42+frames[0].size+7].toInt() xor 1).toByte()
        assertNotEquals(A0FlacWholeFileEvidence.Status.VERIFIED,check(broken).status)
    }
    @Test fun missingMiddleFrameAndDuplicatedFrameAreNotContinuous() {
        assertNotEquals(A0FlacWholeFileEvidence.Status.VERIFIED,
            check(stream(frames[0],frames[2])).status)
        assertNotEquals(A0FlacWholeFileEvidence.Status.VERIFIED,
            check(stream(frames[0],frames[1],frames[1],frames[2])).status)
    }
    @Test fun trailingBytesAndWrongSampleCountFailClosed() {
        assertNotEquals(A0FlacWholeFileEvidence.Status.VERIFIED,
            check(stream(*frames.toTypedArray())+byteArrayOf(1,2,3)).status)
        assertNotEquals(A0FlacWholeFileEvidence.Status.VERIFIED,
            check(stream(*frames.toTypedArray(),total=900)).status)
    }
    @Test fun callerCancellationAndMetadataTruncationFailClosed() {
        assertEquals(A0FlacWholeFileEvidence.Status.CANCELLED,
            check(stream(*frames.toTypedArray())) { true }.status)
        assertNotEquals(A0FlacWholeFileEvidence.Status.VERIFIED,
            check(ByteArray(42)).status)
    }
}
