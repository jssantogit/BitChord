package com.music.bitchord.acquisition.a0

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Looper
import android.os.SystemClock
import com.music.bitchord.data.DebugLog as Log
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

    /** Internal diagnostic categories only; never include filenames or paths. */
    internal enum class Failure {
        INVALID_PRIVATE_SOURCE, SOURCE_READ_FAILURE, HEADER_TOO_SHORT,
        UNRECOGNIZED_AUDIO_HEADER, FILE_EXTENSION_CONFLICT,
        MULTIPLE_AUDIO_TRACKS, NO_AUDIO_TRACK, EXTRACTOR_MIME_CONFLICT,
        DISAGREEING_DURATIONS, DURATION_UNAVAILABLE, INVALID_DURATION,
        SAMPLE_RATE_CONFLICT,
        ZERO_LENGTH_SAMPLE, WRONG_SAMPLE_TRACK, ENCRYPTED_SAMPLE,
        NEGATIVE_SAMPLE_TIME, REGRESSING_SAMPLE_TIME,
        SAMPLE_BYTE_LIMIT_EXCEEDED,
        INCOMPLETE_SAMPLE_SCAN, SOURCE_CHANGED_OR_REVOKED,
        MEDIA_EXTRACTOR_EXCEPTION,
    }

    private fun denied(reason:Failure):A0AutoPostTransferTechnicalGate.Decoded? {
        try {Log.d("BitChord","soulseek_audio_probe refused=${reason.name}")}
        catch(_:Exception) {}
        return null
    }

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
            isCancelled() || Thread.currentThread().isInterrupted)
            return denied(Failure.INVALID_PRIVATE_SOURCE)
        val size=source.length()
        val modification=source.lastModified()
        val head=ByteArray(minOf(size.toInt(),HEADER_BYTES))
        val read=try {
            source.inputStream().use { input ->
                var count=0
                while(count<head.size) {
                    if(isCancelled() || Thread.currentThread().isInterrupted)
                        return denied(Failure.SOURCE_CHANGED_OR_REVOKED)
                    val part=input.read(head,count,head.size-count)
                    if(part<=0)break
                    count+=part
                }
                count
            }
        }catch(_:Exception) {
            // Do not expose transient private file paths or provider errors.
            return denied(Failure.SOURCE_READ_FAILURE)
        }
        if(read<12)return denied(Failure.HEADER_TOO_SHORT)
        val header=A0LocalAudioHeaderEvidence.inspect(head.copyOf(read))
            ?:return denied(Failure.UNRECOGNIZED_AUDIO_HEADER)
        val ext=declaredExtension.trimStart('.')
            .lowercase(java.util.Locale.ROOT)
        val expected=when(header.codec) {
            A0CanonicalAcquisitionIntent.Codec.FLAC -> "flac"
            A0CanonicalAcquisitionIntent.Codec.PCM_WAV -> "wav"
            A0CanonicalAcquisitionIntent.Codec.MP3 -> "mp3"
            A0CanonicalAcquisitionIntent.Codec.AAC_ADTS -> "aac"
            A0CanonicalAcquisitionIntent.Codec.OPUS -> "opus"
            A0CanonicalAcquisitionIntent.Codec.VORBIS -> "ogg"
        }
        if(ext!=expected)return denied(Failure.FILE_EXTENSION_CONFLICT)

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
                if(mime.startsWith("video/",ignoreCase=true))
                    return denied(Failure.MULTIPLE_AUDIO_TRACKS)
                if(mime.startsWith("audio/",ignoreCase=true)) {
                    if(audioIndex>=0)return denied(Failure.MULTIPLE_AUDIO_TRACKS)
                    audioIndex=index
                    track=format
                }
            }
            if(audioIndex<0 || track==null)
                return denied(Failure.NO_AUDIO_TRACK)
            val format=track
            val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
            if(!A0LocalAudioHeaderEvidence.acceptsExtractorMime(header.codec,mime))
                return denied(Failure.EXTRACTOR_MIME_CONFLICT)
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
                abs(extractorDuration-retrieverDuration)>2_000L)
                return denied(Failure.DISAGREEING_DURATIONS)
            val duration=retrieverDuration ?: extractorDuration
                ?:return denied(Failure.DURATION_UNAVAILABLE)
            if(duration !in 15_000L..7_200_000L)
                return denied(Failure.INVALID_DURATION)

            val fromExtractor=intOrNull(MediaFormat.KEY_SAMPLE_RATE)
            if(header.sampleRateHz!=null && fromExtractor!=null &&
                header.sampleRateHz!=fromExtractor)
                return denied(Failure.SAMPLE_RATE_CONFLICT)
            val sampleRate=header.sampleRateHz ?: fromExtractor
            val trackRate=intOrNull(MediaFormat.KEY_BIT_RATE)
                ?.takeIf {it>0}?.div(1000)
            val metadataRate=metadata.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()?.takeIf {it>0}?.div(1000)
            val extractorBitrate=trackRate ?: metadataRate

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
                // A zero-size packet is not permission to ignore its crypto
                // flags or an unexpected source track.
                if((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)
                    return denied(Failure.ENCRYPTED_SAMPLE)
                val reportedTrack=extractor.sampleTrackIndex
                if(amount==0) {
                    if(reportedTrack!=audioIndex && reportedTrack!=-1)
                        return denied(Failure.WRONG_SAMPLE_TRACK)
                    // Only a terminal empty packet is accepted, with enough
                    // positive samples and independently measured duration.
                    val end=!extractor.advance()
                    if(A0AudioSampleScanPolicy.acceptTerminalEmpty(
                            samples,lastUs,duration,end)) {
                        reachedEos=true
                        break
                    }
                    return denied(Failure.ZERO_LENGTH_SAMPLE)
                }
                if(reportedTrack!=audioIndex)
                    return denied(Failure.WRONG_SAMPLE_TRACK)
                val timestamp=extractor.sampleTime
                if(timestamp<0)return denied(Failure.NEGATIVE_SAMPLE_TIME)
                if(timestamp<lastUs)return denied(Failure.REGRESSING_SAMPLE_TIME)
                lastUs=timestamp
                bytes+=amount
                if(bytes>A0ObservedTransferLimits.MAX_AUDIO_BYTES)
                    return denied(Failure.SAMPLE_BYTE_LIMIT_EXCEEDED)
                samples++
                if(!extractor.advance()) {
                    reachedEos=true
                    break
                }
            }
            if(!reachedEos || samples<2 ||
                lastUs<0 || abs(duration-lastUs/1000L)>3_000L)
                return denied(Failure.INCOMPLETE_SAMPLE_SCAN)
            if(source.length()!=size || source.lastModified()!=modification ||
                isCancelled() || Thread.currentThread().isInterrupted)
                return denied(Failure.SOURCE_CHANGED_OR_REVOKED)
            // Opus/Vorbis VBR may omit KEY_BIT_RATE entirely. After a full
            // demux walk, sample bytes / locally inspected duration provide
            // a bounded observed average. Never derive quality from the
            // peer's unverified bitrate or the file's container overhead.
            val bitrate=extractorBitrate ?: when(header.codec) {
                A0CanonicalAcquisitionIntent.Codec.OPUS,
                A0CanonicalAcquisitionIntent.Codec.VORBIS ->
                    A0ObservedAudioBitrate.fromDemuxedSamples(bytes,duration)
                else -> null
            }
            // COMPLETED means full demux walk. It is NOT a complete PCM
            // decode or a source authentication. Policy cannot publish.
            return A0AutoPostTransferTechnicalGate.Decoded(
                codec=header.codec,extension=ext,durationMillis=duration,
                bitrateKbps=bitrate,sampleRateHz=sampleRate,
                bitDepth=header.bitDepth,completed=true)
        }catch(_:Exception) {
            return denied(Failure.MEDIA_EXTRACTOR_EXCEPTION)
        }finally {
            runCatching {metadata.release()}
            runCatching {extractor.release()}
        }
    }
}
