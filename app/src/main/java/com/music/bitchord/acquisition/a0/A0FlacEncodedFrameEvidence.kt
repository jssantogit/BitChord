package com.music.bitchord.acquisition.a0

import java.nio.ByteBuffer

/**
 * Checks a single complete encoded FLAC frame against the codec's actual
 * header/sequence/CRC structure. Never relies on peer names or timestamps.
 * This is NOT a PCM decoder, and cannot by itself approve publication.
 */
internal object A0FlacEncodedFrameEvidence {
    // Table-driven checksums keep large FLAC files within the demux time
    // budget: a per-bit CRC multiplies work by eight for every encoded byte.
    private val crc8Table=IntArray(256) { seed ->
        var c=seed
        repeat(8) {
            c=if((c and 128)!=0)((c shl 1) xor 7) and 255
                else (c shl 1) and 255
        }
        c
    }
    private val crc16Table=IntArray(256) { seed ->
        var c=seed shl 8
        repeat(8) {
            c=if((c and 32768)!=0)
                ((c shl 1) xor 0x8005) and 65535
                else (c shl 1) and 65535
        }
        c
    }
    data class Frame(val number:Long,val sampleCount:Int,val variableBlock:Boolean)
    enum class Fault {
        NONE, INCOMPLETE, BAD_SYNC, RESERVED_FIELD, HEADER_CRC,
        FOOTER_CRC, INVALID_SEQUENCE,
    }
    data class Result(val frame:Frame?,val fault:Fault)

    fun inspect(buffer:ByteBuffer,size:Int):Result {
        fun fail(reason:Fault)=Result(null,reason)
        if(size<10 || size>buffer.capacity())return fail(Fault.INCOMPLETE)
        fun byte(i:Int)=buffer.get(i).toInt() and 255
        if(byte(0)!=0xff || (byte(1) and 0xfe)!=0xf8)
            return fail(Fault.BAD_SYNC)
        val variable=(byte(1) and 1)!=0
        val blockCode=byte(2) ushr 4
        val rateCode=byte(2) and 15
        val channels=byte(3) ushr 4
        val depth=(byte(3) ushr 1) and 7
        if(blockCode==0 || rateCode==15 || channels>10 ||
            depth in setOf(3,7) || (byte(3) and 1)!=0)
            return fail(Fault.RESERVED_FIELD)
        var i=4
        val first=byte(i++)
        val continuation=when {
            first<0x80 -> 0
            (first and 0xe0)==0xc0 -> 1
            (first and 0xf0)==0xe0 -> 2
            (first and 0xf8)==0xf0 -> 3
            (first and 0xfc)==0xf8 -> 4
            (first and 0xfe)==0xfc -> 5
            first==0xfe -> 6
            else -> return fail(Fault.INVALID_SEQUENCE)
        }
        var number=if(continuation==0)first.toLong()
            else (first and (0x7f ushr (continuation+1))).toLong()
        if(i+continuation>=size-2)return fail(Fault.INCOMPLETE)
        repeat(continuation) {
            val next=byte(i++)
            if((next and 0xc0)!=0x80)return fail(Fault.INVALID_SEQUENCE)
            number=(number shl 6) or (next and 63).toLong()
        }
        val block=when(blockCode) {
            1 -> 192
            in 2..5 -> 576 shl (blockCode-2)
            6 -> {
                if(i+1>size-3)return fail(Fault.INCOMPLETE)
                byte(i++)+1
            }
            7 -> {
                if(i+2>size-3)return fail(Fault.INCOMPLETE)
                ((byte(i++) shl 8) or byte(i++))+1
            }
            else -> 256 shl (blockCode-8)
        }
        i+=when(rateCode) {12->1;13,14->2;else->0}
        if(i>=size-2)return fail(Fault.INCOMPLETE)
        var crc=0
        for(k in 0 until i)crc=crc8Table[(crc xor byte(k)) and 255]
        if(crc!=byte(i))return fail(Fault.HEADER_CRC)
        var crc16=0
        for(k in 0 until size-2) {
            crc16=((crc16 shl 8) xor
                crc16Table[((crc16 ushr 8) xor byte(k)) and 255]) and 65535
        }
        if(crc16!=((byte(size-2) shl 8) or byte(size-1)))
            return fail(Fault.FOOTER_CRC)
        return Result(Frame(number,block,variable),Fault.NONE)
    }
}

/**
 * A CRC-valid frame is not enough: the whole local stream must carry every
 * consecutive sample exactly once. No synthetic packet->frame assumption.
 */
internal class A0FlacFrameSequenceEvidence {
    enum class Problem {
        NONE, PACKET_UNREADABLE, BLOCK_OUTSIDE_STREAMINFO,
        NUMBERING_INCONSISTENT, SAMPLE_COUNT_EXCEEDED,
    }
    var problem=Problem.NONE;private set
    var packets=0;private set
    var pcmSamples=0L;private set
    var headerCrcFailures=0;private set
    var footerCrcFailures=0;private set
    var badSyncPackets=0;private set
    private var strategy:Boolean?=null

    fun observe(
        result:A0FlacEncodedFrameEvidence.Result,
        minBlock:Int,maxBlock:Int,limit:Long,
    ) {
        packets++
        if(result.frame==null) {
            if(result.fault==A0FlacEncodedFrameEvidence.Fault.HEADER_CRC)
                headerCrcFailures++
            if(result.fault==A0FlacEncodedFrameEvidence.Fault.FOOTER_CRC)
                footerCrcFailures++
            if(result.fault==A0FlacEncodedFrameEvidence.Fault.BAD_SYNC)
                badSyncPackets++
            if(problem==Problem.NONE)problem=Problem.PACKET_UNREADABLE
            return
        }
        if(problem!=Problem.NONE)return
        val frame=result.frame
        // Only the last frame may have a shorter block than STREAMINFO's
        // advertised normal block size.
        if(frame.sampleCount !in minBlock..maxBlock &&
            !(frame.sampleCount in 1..maxBlock &&
                frame.sampleCount+pcmSamples==limit)) {
            problem=Problem.BLOCK_OUTSIDE_STREAMINFO
            return
        }
        if((strategy!=null && strategy!=frame.variableBlock) ||
            frame.number != (if(frame.variableBlock) pcmSamples
                else packets.toLong()-1L)) {
            problem=Problem.NUMBERING_INCONSISTENT
            return
        }
        strategy=frame.variableBlock
        pcmSamples+=frame.sampleCount
        if(pcmSamples>limit)problem=Problem.SAMPLE_COUNT_EXCEEDED
    }

    fun verified(expected:Long):Boolean =
        problem==Problem.NONE && packets>1 && pcmSamples==expected
}
