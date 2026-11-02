package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.InflaterInputStream

/**
 * Soulseek P code 36/37, strictly read-only and bounded.
 *
 * The response is an untrusted peer claim. Only the requested directory is
 * considered; recursively included subfolders cannot be silently treated as
 * the requested album. Nothing here authorizes a download or identifies
 * an audio master.
 */
internal object A0PeerFolderContentsWire {
    private const val MAX_WIRE=1_048_576
    private const val MAX_INFLATED=2_097_152
    private const val MAX_FILES=512
    private const val MAX_FOLDERS=64
    private const val MAX_ATTRIBUTES=32
    private const val MAX_STRING=2048

    fun request(token:Int,folder:String):ByteArray {
        require(token>0)
        require(safeFolder(folder))
        val body=ByteArrayOutputStream()
        u32(body,token);string(body,folder)
        val frame=ByteArrayOutputStream()
        u32(frame,body.size()+4);u32(frame,36);frame.write(body.toByteArray())
        return frame.toByteArray()
    }

    fun parse(frame:ByteArray,expectedToken:Int,folder:String):
        List<A0SoulseekSearchFile> {
        require(expectedToken>0)
        require(safeFolder(folder))
        if(frame.size !in 12..MAX_WIRE)bad()
        val header=Fields(frame)
        if(header.u32()!=frame.size-4 || header.u32()!=37)bad()
        val zipped=frame.copyOfRange(8,frame.size)
        val inflated=try {
            InflaterInputStream(ByteArrayInputStream(zipped)).use { input ->
                val output=ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true) {
                    val count=input.read(buffer)
                    if(count<0)break
                    if(count==0)continue
                    if(count>MAX_INFLATED-output.size())bad()
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
        }catch(_:IOException) { bad() }
        val values=Fields(inflated)
        if(values.u32()!=expectedToken || values.string()!=folder)bad()
        val folders=values.u32()
        if(folders !in 0..MAX_FOLDERS)bad()
        var total=0
        val result=ArrayList<A0SoulseekSearchFile>()
        repeat(folders) {
            val foundFolder=values.string()
            val count=values.u32()
            if(count<0 || count>MAX_FILES-total)bad()
            total+=count
            repeat(count) {
                if(values.u8()!=1)bad()
                val basename=values.string()
                val size=values.u64()
                val ext=values.string()
                val attributesCount=values.u32()
                if(attributesCount !in 0..MAX_ATTRIBUTES)bad()
                val attrs=LinkedHashMap<Int,Int>(attributesCount)
                repeat(attributesCount) { attrs[values.u32()]=values.u32() }
                // A response may include child folders. They remain out of
                // scope, even when their paths appear to share release names.
                if(foundFolder==folder) {
                    if(!safeBasename(basename)||size<1)bad()
                    result.add(A0SoulseekSearchFile(
                        "$folder\\$basename",size,ext,attrs))
                }
            }
        }
        if(values.remaining!=0)bad()
        return result
    }

    private fun safeFolder(value:String):Boolean =
        value.length in 1..1024 && value.none { it.code<32 } &&
        !value.contains("..") && !value.startsWith('/') &&
        !value.startsWith('\\') &&
        A0ModernIncomingTransfer.isSafeRemoteName("$value\\probe.flac")

    private fun safeBasename(value:String):Boolean =
        value.length in 1..255 && value!="." && value!=".." &&
        !value.contains('/') && !value.contains('\\') &&
        value.none { it.code<32 || it in '\u202a'..'\u202e' ||
            it in '\u2066'..'\u2069' }

    private fun u32(out:ByteArrayOutputStream,value:Int) {
        repeat(4) { out.write((value ushr (8*it)) and 255) }
    }
    private fun string(out:ByteArrayOutputStream,value:String) {
        val b=value.toByteArray(Charsets.UTF_8)
        require(b.size in 1..MAX_STRING)
        u32(out,b.size);out.write(b)
    }
    private fun bad():Nothing=throw IOException("Invalid bounded peer folder contents")

    private class Fields(private val b:ByteArray) {
        private var pos=0
        val remaining:Int get()=b.size-pos
        fun u8():Int {
            if(remaining<1)bad()
            return b[pos++].toInt() and 255
        }
        fun u32():Int=u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
        fun u64():Long {
            var v=0L
            repeat(8) { v=v or (u8().toLong() shl (it*8)) }
            if(v<0)bad()
            return v
        }
        fun string():String {
            val n=u32()
            if(n<0 || n>MAX_STRING || n>remaining)bad()
            val raw=b.copyOfRange(pos,pos+n)
            pos+=n
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString()
            }catch(_:CharacterCodingException) { bad() }
        }
    }
}
