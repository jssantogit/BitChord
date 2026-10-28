package com.music.bitchord.acquisition.a0

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.math.abs

/**
 * LOCAL, bounded container inspection for the future opt-in auto engine.
 *
 * Reads the actual FLAC/WAV header and scans the complete encoded sample
 * sequence with Android MediaExtractor. This is stronger than peer claims or
 * a magic-byte test, but it does NOT decode all samples to PCM, fingerprint
 * the audio, authenticate the release or publish a file. The pure
 * A0AutoPostTransferTechnicalGate still has no publication authority.
 *
 * Only private, closed receipts are eligible. Never log file paths/metadata.
 */
internal object A0AndroidAudioTechnicalProbe {
    private const val MAX_SCAN_MILLIS=30_000L
    private const val MAX_SAMPLES=400_000
    private const val SAMPLE_BUFFER_BYTES=1 shl 20
    private const val HEADER_BYTES=65_536

    fun inspect(
        source:File,
        declaredExtension:String,
        isCancelled:()->Boolean={false},
    ):A0AutoPostTransferTechnicalGate.Decoded? {
        check(Looper.myLooper()!=Looper.getMainLooper()) {
            "Audio receipt inspection cannot run on Android main thread"
        }
        if(!source.isFile || Files.isSymbolicLink(source.toPath()) ||
            source.length() !in 44L..A0ObservedTransferLimits.MAX_AUDIO_BYTES ||
            isCancelled() || Thread.currentThread().isInterrupted)return null
        val size=source.length()
        val modification=source.lastModified()
        val head=ByteArray(minOf(size.toInt(),HEADER_BYTES))
        val read=try {
            source.inputStream().use { input ->
                var count=0
                while(count<head.size) {
                    if(isCancelled() || Thread.currentThread().isInterrupted)
                        return null
                    val part=input.read(head,count,head.size-count)
                    if(part<=0)break
                    count+=part
                }
                count
            }
        }catch(_:Exception) {
            // Do not expose transient private file paths or provider errors.
            return null
        }
        if(read<12)return null
        val header=A0LocalAudioHeaderEvidence.inspect(head.copyOf(read))
            ?:return null
        val ext=declaredExtension.trimStart('.')
            .lowercase(java.util.Locale.ROOT)
        val expected=when(header.codec) {
            A0CanonicalAcquisitionIntent.Codec.FLAC -> "flac"
            A0CanonicalAcquisitionIntent.Codec.PCM_WAV -> "wav"
            A0CanonicalAcquisitionIntent.Codec.MP3 -> "mp3"
            A0CanonicalAcquisitionIntent.Codec.AAC_ADTS -> "aac"
        }
        if(ext!=expected)return null

        val extractor=MediaExtractor()
        val metadata=MediaMetadataRetriever()
        try {
            extractor.setDataSource(source.absolutePath)
            var audioIndex=-1
            var track:MediaFormat?=null
            for(index in 0 until extractor.trackCount) {
                val format=extractor.getTrackFormat(index)
                val mime=if(format.containsKey(MediaFormat.KEY_MIME))
                    format.getString(MediaFormat.KEY_MIME).orEmpty() else ""
                if(mime.startsWith("video/",ignoreCase=true))return null
                if(mime.startsWith("audio/",ignoreCase=true)) {
                    if(audioIndex>=0)return null
                    audioIndex=index
                    track=format
                }
            }
            if(audioIndex<0 || track==null)return null
            val format=track
            val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
            if(!A0LocalAudioHeaderEvidence.acceptsExtractorMime(header.codec,mime))
                return null
            fun longOrNull(key:String):Long? =
                if(format.containsKey(key))runCatching {
                    format.getLong(key)
                }.getOrNull() else null
            fun intOrNull(key:String):Int? =
                if(format.containsKey(key))runCatching {
                    format.getInteger(key)
                }.getOrNull() else null
            metadata.setDataSource(source.absolutePath)
            val extractorDuration=longOrNull(MediaFormat.KEY_DURATION)
                ?.takeIf {it>0L}?.div(1000L)
            val retrieverDuration=metadata.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf {it>0L}
            if(extractorDuration!=null && retrieverDuration!=null &&
                abs(extractorDuration-retrieverDuration)>2_000L)return null
            val duration=retrieverDuration ?: extractorDuration ?: return null
            if(duration !in 15_000L..7_200_000L)return null

            val fromExtractor=intOrNull(MediaFormat.KEY_SAMPLE_RATE)
            if(header.sampleRateHz!=null && fromExtractor!=null &&
                header.sampleRateHz!=fromExtractor)return null
            val sampleRate=header.sampleRateHz ?: fromExtractor
            val trackRate=intOrNull(MediaFormat.KEY_BIT_RATE)
                ?.takeIf {it>0}?.div(1000)
            val metadataRate=metadata.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()?.takeIf {it>0}?.div(1000)
            val bitrate=trackRate ?: metadataRate

            extractor.selectTrack(audioIndex)
            val input=ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES)
            val endBy=SystemClock.elapsedRealtime()+MAX_SCAN_MILLIS
            var samples=0
            var bytes=0L
            var lastUs=-1L
            var reachedEos=false
            while(samples<MAX_SAMPLES &&
                SystemClock.elapsedRealtime()<endBy &&
                !isCancelled() && !Thread.currentThread().isInterrupted) {
                input.clear()
                val amount=extractor.readSampleData(input,0)
                if(amount<0) {
                    reachedEos=true
                    break
                }
                if(amount==0 || extractor.sampleTrackIndex!=audioIndex ||
                    (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)
                    return null
                val timestamp=extractor.sampleTime
                if(timestamp<0 || timestamp<lastUs)return null
                lastUs=timestamp
                bytes+=amount
                if(bytes>A0ObservedTransferLimits.MAX_AUDIO_BYTES)return null
                samples++
                if(!extractor.advance()) {
                    reachedEos=true
                    break
                }
            }
            if(!reachedEos || samples<2 ||
                lastUs<0 || abs(duration-lastUs/1000L)>3_000L ||
                source.length()!=size || source.lastModified()!=modification ||
                isCancelled() || Thread.currentThread().isInterrupted)return null
            // COMPLETED means full demux walk. It is NOT a complete PCM
            // decode or a source authentication. Policy cannot publish.
            return A0AutoPostTransferTechnicalGate.Decoded(
                codec=header.codec,extension=ext,durationMillis=duration,
                bitrateKbps=bitrate,sampleRateHz=sampleRate,
                bitDepth=header.bitDepth,completed=true)
        }catch(_:Exception) {
            return null
        }finally {
            runCatching {metadata.release()}
            runCatching {extractor.release()}
        }
    }
}
