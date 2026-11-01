package com.music.bitchord.acquisition.a0

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Independent, bounded, one-pass FLAC frame-integrity inspection of the
 * PRIVATE, CLOSED LOCAL file. Android's MediaExtractor can return synthetic
 * or regrouped packets with all-zero PTS; those packets are not FLAC frame
 * boundaries and must NEVER be used as evidence of frame completeness.
 *
 * This verifies FLAC metadata, per-frame header CRC8, whole-frame CRC16,
 * consecutive encoded frame numbering and STREAMINFO PCM sample coverage.
 * It does not decode PCM or identify the music/master. A3 and the technical
 * quality/duration gate remain mandatory before any publication.
 */
internal object A0FlacWholeFileEvidence {
    enum class Status {
        VERIFIED, INVALID_SOURCE, INVALID_METADATA, MISSING_FRAME,
        FRAME_CRC_MISMATCH, FRAME_NUMBERING, BLOCK_SIZE_CONFLICT,
        PCM_SAMPLE_MISMATCH, CANCELLED, TIME_LIMIT, SOURCE_CHANGED,
        IO_FAILURE,
    }
    data class Result(
        val status:Status,
        val frames:Int=0,
        val pcmSamples:Long=0,
        val scannedBytes:Long=0,
        val frameCrcErrors:Int=0,
        val headerCandidates:Int=0,
    )

    private const val MAX_FILE_BYTES=256L*1024L*1024L
    private const val MAX_METADATA_BYTES=32L*1024L*1024L
    private const val MAX_METADATA_BLOCKS=128
    private const val MAX_FRAMES=400_000
    private const val SCAN_BUDGET_NS=25_000_000_000L
    private val crc8Table=IntArray(256) { seed ->
        var v=seed
        repeat(8) {
            v=if(v and 128!=0)((v shl 1) xor 7) and 255
                else (v shl 1) and 255
        }
        v
    }
    private val crc16Table=IntArray(256) { seed ->
        var v=seed shl 8
        repeat(8) {
            v=if(v and 32768!=0)
                ((v shl 1) xor 0x8005) and 65535
                else (v shl 1) and 65535
        }
        v
    }
    private fun crc16Next(previous:Int,next:Int):Int =
        ((previous shl 8) xor
            crc16Table[((previous ushr 8) xor next) and 255]) and 65535

    private fun u8(b:ByteArray,index:Int)=b[index].toInt() and 255
    private fun readExactly(input:InputStream,n:Int):ByteArray? {
        val data=ByteArray(n)
        var count=0
        while(count<n) {
            val got=input.read(data,count,n-count)
            if(got<=0)return null
            count+=got
        }
        return data
    }
    private fun skipExactly(input:InputStream,bytes:Long):Boolean {
        var left=bytes
        while(left>0) {
            val n=input.skip(left)
            if(n>0)left-=n
            else if(input.read()<0)return false
            else left--
        }
        return true
    }
    private data class Header(val number:Long,val block:Int,val variable:Boolean)
    private fun header(
        raw:ByteArray,size:Int,rateHz:Int,channels:Int,bitDepth:Int,
    ):Header? {
        if(size<6 || u8(raw,0)!=255 || (u8(raw,1) and 254)!=248)
            return null
        val variable=(u8(raw,1) and 1)!=0
        val blockCode=u8(raw,2) ushr 4
        val rateCode=u8(raw,2) and 15
        val channelCode=u8(raw,3) ushr 4
        val depthCode=(u8(raw,3) ushr 1) and 7
        if(blockCode==0 || rateCode==15 || channelCode>10 ||
            depthCode in setOf(3,7) || (u8(raw,3) and 1)!=0)
            return null
        if((if(channelCode<8)channelCode+1 else 2)!=channels)
            return null
        val depth=when(depthCode) {
            0->bitDepth;1->8;2->12;4->16;5->20;6->24;else->return null
        }
        if(depth!=bitDepth)return null
        var i=4
        val first=u8(raw,i++)
        val following=when {
            first<128->0
            (first and 224)==192->1
            (first and 240)==224->2
            (first and 248)==240->3
            (first and 252)==248->4
            (first and 254)==252->5
            first==254->6
            else->return null
        }
        if(i+following>=size)return null
        var number=if(following==0)first.toLong()
            else (first and (127 ushr (following+1))).toLong()
        repeat(following) {
            val b=u8(raw,i++)
            if((b and 192)!=128)return null
            number=(number shl 6) or (b and 63).toLong()
        }
        val block=when(blockCode) {
            1->192
            in 2..5->576 shl (blockCode-2)
            6-> {if(i>=size)return null;u8(raw,i++)+1}
            7-> {
                if(i+1>=size)return null
                ((u8(raw,i++) shl 8) or u8(raw,i++))+1
            }
            else->256 shl (blockCode-8)
        }
        val explicitRate=when(rateCode) {
            0->rateHz
            1->88200;2->176400;3->192000;4->8000;5->16000
            6->22050;7->24000;8->32000;9->44100
            10->48000;11->96000
            12->{if(i>=size)return null;u8(raw,i++)*1000}
            13->{
                if(i+1>=size)return null
                (u8(raw,i++) shl 8) or u8(raw,i++)
            }
            14->{
                if(i+1>=size)return null
                ((u8(raw,i++) shl 8) or u8(raw,i++))*10
            }
            else->return null
        }
        if(explicitRate!=rateHz || i>=size)return null
        var crc=0
        for(k in 0 until i)crc=crc8Table[(crc xor u8(raw,k)) and 255]
        if(crc!=u8(raw,i))return null
        return Header(number,block,variable)
    }

    fun inspect(
        file:File,
        expectedFixedBlock:Int,
        expectedPcmSamples:Long,
        expectedRateHz:Int,
        isCancelled:()->Boolean={false},
    ):Result {
        val initialSize=file.length()
        val initialModified=file.lastModified()
        if(!file.isFile || initialSize !in 44L..MAX_FILE_BYTES ||
            expectedFixedBlock !in 16..65535 ||
            expectedPcmSamples<=0 || expectedRateHz !in 8000..384000)
            return Result(Status.INVALID_SOURCE)
        if(isCancelled() || Thread.currentThread().isInterrupted)
            return Result(Status.CANCELLED)
        val started=System.nanoTime()
        var scanned=0L
        var frames=0
        var pcmSamples=0L
        var crcErrors=0
        var candidates=0
        fun finish(status:Status):Result=Result(
            status,frames,pcmSamples,scanned,crcErrors,candidates)
        fun stopped():Status?=when {
            isCancelled() || Thread.currentThread().isInterrupted ->
                Status.CANCELLED
            System.nanoTime()-started>SCAN_BUDGET_NS -> Status.TIME_LIMIT
            else->null
        }
        try {
            BufferedInputStream(FileInputStream(file),131072).use { input ->
                val magic=readExactly(input,4)
                    ?:return finish(Status.INVALID_METADATA)
                if(!magic.contentEquals("fLaC".toByteArray()))
                    return finish(Status.INVALID_METADATA)
                var isLast=false
                var blocks=0
                var metadataBytes=4L
                var channels=0
                var depth=0
                do {
                    stopped()?.let {return finish(it)}
                    val block=readExactly(input,4)
                        ?:return finish(Status.INVALID_METADATA)
                    val type=u8(block,0) and 127
                    val length=(u8(block,1) shl 16) or
                        (u8(block,2) shl 8) or u8(block,3)
                    metadataBytes+=4L+length
                    blocks++
                    if(blocks>MAX_METADATA_BLOCKS ||
                        metadataBytes>MAX_METADATA_BYTES ||
                        (blocks==1 && (type!=0 || length!=34)))
                        return finish(Status.INVALID_METADATA)
                    isLast=(u8(block,0) and 128)!=0
                    if(blocks==1) {
                        val data=readExactly(input,34)
                            ?:return finish(Status.INVALID_METADATA)
                        val minBlock=(u8(data,0) shl 8) or u8(data,1)
                        val maxBlock=(u8(data,2) shl 8) or u8(data,3)
                        val rate=(u8(data,10) shl 12) or
                            (u8(data,11) shl 4) or (u8(data,12) ushr 4)
                        channels=((u8(data,12) ushr 1) and 7)+1
                        depth=(((u8(data,12) and 1) shl 4) or
                            (u8(data,13) ushr 4))+1
                        val total=((u8(data,13) and 15).toLong() shl 32) or
                            (u8(data,14).toLong() shl 24) or
                            (u8(data,15).toLong() shl 16) or
                            (u8(data,16).toLong() shl 8) or u8(data,17).toLong()
                        if(minBlock!=expectedFixedBlock ||
                            maxBlock!=expectedFixedBlock ||
                            total!=expectedPcmSamples || rate!=expectedRateHz ||
                            channels !in 1..8 || depth !in 8..32)
                            return finish(Status.INVALID_METADATA)
                    } else if(!skipExactly(input,length.toLong()))
                        return finish(Status.INVALID_METADATA)
                }while(!isLast)
                scanned=metadataBytes
                var crc=0
                var startedFrame=false
                var mode:Boolean?=null
                val headerBytes=ByteArray(16)
                var processedSinceCheck=0
                while(true) {
                    if(processedSinceCheck>=65536) {
                        stopped()?.let {return finish(it)}
                        processedSinceCheck=0
                    }
                    val first=input.read()
                    if(first<0)break
                    // Look ahead without consuming bytes: a valid next header
                    // only starts AFTER the previous frame CRC16 reaches zero.
                    if(first==255) {
                        input.mark(24)
                        headerBytes[0]=255.toByte()
                        var available=1
                        while(available<headerBytes.size) {
                            val n=input.read(
                                headerBytes,available,headerBytes.size-available)
                            if(n<=0)break
                            available+=n
                        }
                        input.reset()
                        val next=header(headerBytes,available,
                            expectedRateHz,channels,depth)
                        if(next!=null) {
                            candidates++
                            if(startedFrame && crc!=0) {
                                crcErrors++
                                return finish(Status.FRAME_CRC_MISMATCH)
                            }
                            if(mode!=null && mode!=next.variable)
                                return finish(Status.FRAME_NUMBERING)
                            val expectedNumber=if(next.variable)
                                pcmSamples else frames.toLong()
                            if(next.number!=expectedNumber)
                                return finish(Status.FRAME_NUMBERING)
                            if(next.block!=expectedFixedBlock &&
                                !(next.block in 1 until expectedFixedBlock &&
                                    pcmSamples+next.block==expectedPcmSamples))
                                return finish(Status.BLOCK_SIZE_CONFLICT)
                            frames++
                            if(frames>MAX_FRAMES)return finish(Status.FRAME_NUMBERING)
                            pcmSamples+=next.block
                            if(pcmSamples>expectedPcmSamples)
                                return finish(Status.PCM_SAMPLE_MISMATCH)
                            startedFrame=true
                            mode=next.variable
                            crc=0
                        }
                    }
                    if(startedFrame)crc=crc16Next(crc,first)
                    else if(scanned-metadataBytes>1024)
                        return finish(Status.MISSING_FRAME)
                    scanned++
                    processedSinceCheck++
                }
                stopped()?.let {return finish(it)}
                if(!startedFrame || frames<2)return finish(Status.MISSING_FRAME)
                if(crc!=0) {
                    crcErrors++
                    return finish(Status.FRAME_CRC_MISMATCH)
                }
                if(pcmSamples!=expectedPcmSamples)
                    return finish(Status.PCM_SAMPLE_MISMATCH)
                if(scanned!=initialSize || file.length()!=initialSize ||
                    file.lastModified()!=initialModified)
                    return finish(Status.SOURCE_CHANGED)
                return finish(Status.VERIFIED)
            }
        }catch(error:java.io.IOException) {
            return finish(Status.IO_FAILURE)
        }catch(error:SecurityException) {
            return finish(Status.IO_FAILURE)
        }
    }
}
