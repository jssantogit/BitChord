package com.music.bitchord.download

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * A2 bridge from an already completed, private transport receipt to a pending
 * Android audio destination. This class does not initiate a Soulseek download.
 *
 * Source SHA-256 is a byte-integrity observation, NOT independent authenticity
 * or licensing evidence. Header sniffing is a conservative container check,
 * NOT proof that Android can decode the complete content.
 */
internal object A2LocalAudioPublisher {
    private const val MAX_BYTES=com.music.bitchord.acquisition.a0.A0ObservedTransferLimits.MAX_AUDIO_BYTES
    private val DIGEST=Regex("[0-9a-fA-F]{64}")
    private val BAD_NAME=Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]""")

    data class Published(
        val contentUri:String,
        val byteCount:Long,
        val sha256Hex:String,
        val mimeType:String
    )

    interface DestinationFactory {
        /** Implementations must fail closed on name collisions and unsafe roots. */
        @Throws(IOException::class)
        fun createPending(name:String,mime:String):PendingDestination
    }
    interface PendingDestination {
        fun openStream():OutputStream
        /** Make file visible only when the copy has been checked and closed. */
        fun publish():String
        /** Idempotent best-effort removal, even after a failed publish. */
        fun abort()
    }

    /** Deliberate flat layout; any hierarchical path is rejected before writing. */
    fun publish(
        source:File,
        fileName:String,
        expectedBytes:Long,
        observedSha256Hex:String,
        destination:DestinationFactory,
        isCancelled:()->Boolean={false}
    ):Published {
        require(fileName.length in 1..180 && fileName.trim()==fileName &&
            fileName !in setOf(".","..") && !BAD_NAME.containsMatchIn(fileName) &&
            !fileName.endsWith(".part",ignoreCase=true)) {
            "A2 requires a safe flat destination name"
        }
        require(DIGEST.matches(observedSha256Hex)){"A2 requires a complete observed SHA-256"}
        if(expectedBytes !in 44..MAX_BYTES || !source.isFile ||
            Files.isSymbolicLink(source.toPath()) || source.length()!=expectedBytes) {
            throw IOException("A2 private receipt byte count is not valid")
        }
        if(isCancelled())throw IOException("A2 publication cancelled")
        val header=ByteArray(16)
        source.inputStream().use { input ->
            if(input.read(header)!=header.size)
                throw IOException("A2 incomplete audio header")
        }
        val mime=classify(header,fileName)
            ?: throw IOException("A2 unsupported audio signature or extension")
        val pending=destination.createPending(fileName,mime)
        var published=false
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            var total=0L
            source.inputStream().buffered().use { input ->
                pending.openStream().buffered().use { output ->
                    val buffer=ByteArray(8192)
                    while(true) {
                        if(isCancelled() || Thread.currentThread().isInterrupted)
                            throw IOException("A2 publication cancelled")
                        val read=input.read(buffer)
                        if(read<0)break
                        if(read==0)continue
                        total+=read
                        if(total>expectedBytes)throw IOException("A2 receipt grew during publication")
                        output.write(buffer,0,read)
                        digest.update(buffer,0,read)
                    }
                    output.flush()
                }
            }
            if(isCancelled() || Thread.currentThread().isInterrupted)
                throw IOException("A2 publication cancelled")
            if(total!=expectedBytes)throw IOException("A2 short local publication")
            val observedDigest=digest.digest().joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
            if(!observedDigest.equals(observedSha256Hex,ignoreCase=true))
                throw IOException("A2 private receipt SHA-256 mismatch")
            val uri=pending.publish()
            if(!uri.startsWith("content://"))
                throw IOException("A2 published destination did not return a content URI")
            published=true
            return Published(uri,total,observedDigest,mime)
        }finally{
            if(!published)runCatching { pending.abort() }
        }
    }

    private fun classify(header:ByteArray,name:String):String? {
        val extension=name.substringAfterLast('.',missingDelimiterValue="").lowercase()
        val marker={at:Int,text:String->
            header.copyOfRange(at,at+text.length).contentEquals(text.toByteArray(Charsets.US_ASCII))
        }
        return when {
            extension=="wav" && marker(0,"RIFF") && marker(8,"WAVE")->"audio/x-wav"
            extension=="flac" && marker(0,"fLaC")->"audio/flac"
            // ADTS AAC only; this does NOT grant ISO-BMFF .m4a/ALAC support.
            // Validate sync, layer, sample-rate index and 13-bit frame size.
            extension=="aac" && (header[0].toInt() and 255)==0xff &&
                (header[1].toInt() and 0xf6)==0xf0 &&
                (header[2].toInt() and 0x3c)!=0x3c &&
                (((header[3].toInt() and 3) shl 11) or
                    ((header[4].toInt() and 255) shl 3) or
                    ((header[5].toInt() and 0xe0) ushr 5))>=7 -> "audio/aac"
            extension=="mp3" && marker(0,"ID3")->"audio/mpeg"
            // MPEG frame sync: 11 set bits, valid version, layer, bitrate and rate.
            extension=="mp3" && (header[0].toInt() and 255)==0xff &&
                (header[1].toInt() and 0xe0)==0xe0 &&
                (header[1].toInt() and 0x18)!=0x08 &&
                (header[1].toInt() and 0x06)!=0x00 &&
                (header[2].toInt() and 0xf0) !in listOf(0x00,0xf0) &&
                (header[2].toInt() and 0x0c)!=0x0c ->"audio/mpeg"
            else->null
        }
    }
}
