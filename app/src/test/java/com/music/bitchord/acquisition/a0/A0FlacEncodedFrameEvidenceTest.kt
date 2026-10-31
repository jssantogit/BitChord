package com.music.bitchord.acquisition.a0

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class A0FlacEncodedFrameEvidenceTest {
    private fun crc8(data:ByteArray):Int {
        var crc=0
        for(v in data) { crc=crc xor (v.toInt() and 255)
            repeat(8){ crc=if(crc and 128!=0)((crc shl 1) xor 7) and 255 else (crc shl 1) and 255 }
        }; return crc
    }
    private fun crc16(data:ByteArray):Int {
        var crc=0
        for(v in data) { crc=crc xor ((v.toInt() and 255) shl 8)
            repeat(8){ crc=if(crc and 32768!=0)((crc shl 1) xor 0x8005) and 65535 else (crc shl 1) and 65535 }
        }; return crc
    }
    private fun packet(number:Int):ByteArray {
        // A checksum-bearing frame, not a full bitstream-decoding fixture.
        val header=byteArrayOf(0xff.toByte(),0xf8.toByte(),0x89.toByte(),0x18,number.toByte())
        val body=header+byteArrayOf(crc8(header).toByte(),1,2,3,4,5)
        val footer=crc16(body)
        return body+byteArrayOf((footer ushr 8).toByte(),footer.toByte())
    }
    private fun checked(bytes:ByteArray)=A0FlacEncodedFrameEvidence.inspect(
        ByteBuffer.allocateDirect(bytes.size).apply {put(bytes);clear()},bytes.size)

    @Test fun acceptsCompleteCrcCheckedConsecutiveEncodedFrames() {
        val tracker=A0FlacFrameSequenceEvidence()
        for(n in 0..3)tracker.observe(checked(packet(n)),256,256,1024)
        assertEquals(A0FlacFrameSequenceEvidence.Problem.NONE,tracker.problem)
        assertEquals(1024L,tracker.pcmSamples)
        assertTrue(tracker.verified(1024))
        assertFalse(tracker.verified(1280))
    }
    @Test fun corruptFrameCannotAuthorizeTimestampRecovery() {
        val broken=packet(0).also {it[it.lastIndex-4]=(it[it.lastIndex-4].toInt() xor 1).toByte()}
        val evidence=checked(broken)
        assertNull(evidence.frame)
        assertEquals(A0FlacEncodedFrameEvidence.Fault.FOOTER_CRC,evidence.fault)
        val tracker=A0FlacFrameSequenceEvidence()
        tracker.observe(evidence,256,256,256)
        assertEquals(A0FlacFrameSequenceEvidence.Problem.PACKET_UNREADABLE,tracker.problem)
        assertFalse(tracker.verified(256))
        assertEquals(1,tracker.footerCrcFailures)
    }
    @Test fun skipsOrDuplicatesRemainBlockedEvenWithValidCrc() {
        val tracker=A0FlacFrameSequenceEvidence()
        tracker.observe(checked(packet(0)),256,256,1024)
        tracker.observe(checked(packet(2)),256,256,1024)
        assertEquals(A0FlacFrameSequenceEvidence.Problem.NUMBERING_INCONSISTENT,tracker.problem)
        assertFalse(tracker.verified(1024))
    }
    @Test fun garbageDemuxPacketIsNotPretendedToBeFlacFrame() {
        val bad=checked(ByteArray(128))
        assertNull(bad.frame)
        assertEquals(A0FlacEncodedFrameEvidence.Fault.BAD_SYNC,bad.fault)
    }
}
