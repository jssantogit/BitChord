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
        FLAC_TIMELINE_UNVERIFIED,
        SAMPLE_BYTE_LIMIT_EXCEEDED,
        INCOMPLETE_SAMPLE_SCAN, SOURCE_CHANGED_OR_REVOKED,
        MEDIA_EXTRACTOR_EXCEPTION,
    }

    private enum class Step { RECEIPT, HEADER, EXTRACTOR, TRACKS, DURATION, SCAN, FINAL }
    private class ProbeMetrics(val scope:Int,val start:Long) {
        var step=Step.RECEIPT
        var samples=0
        var toleratedBacksteps=0
        var backstepMillis=-1L
        var endGapMillis=-1L
        var resetEvents=0
        var timelineVerdict:A0FlacTimelineRecoveryPolicy.Verdict?=null
        var fixedFlacBlockAvailable=false
        var flacTotalSampleCountAvailable=false
        var termination:A0ProbeTermination.Reason?=null
        var fault:A0AcquisitionDiagnostics.Fault?=null
    }
    private fun denied(reason:Failure,m:ProbeMetrics)
        :A0AutoPostTransferTechnicalGate.Decoded? {
        val elapsed=SystemClock.elapsedRealtime()-m.start
        runCatching {
            Log.d("BitChord",
                "soulseek_audio_probe refused=${reason.name}" +
                    " step=${m.step.name} samples=${m.samples.coerceIn(0,400000)}" +
                    " small_backsteps=${m.toleratedBacksteps}" +
                    " regression_ms=${m.backstepMillis.coerceIn(-1L,7200000L)}" +
                    " reset_events=${m.resetEvents.coerceIn(0,400000)}" +
                    " flac_fixed_block=${m.fixedFlacBlockAvailable}" +
                    " flac_total_samples=${m.flacTotalSampleCountAvailable}" +
                    " timeline=${m.timelineVerdict?.name ?: "NONE"}" +
                    " end_gap_ms=${m.endGapMillis.coerceIn(-1L,7200000L)}" +
                    " termination=${m.termination?.name ?: "NONE"}" +
                    " fault=${m.fault?.name ?: "NONE"}" +
                    " elapsed_ms=${elapsed.coerceIn(0L,600000L)}" +
                    " scope=${m.scope}")
        }
        A0AcquisitionDiagnostics.emit(m.scope,
            A0AcquisitionDiagnostics.Phase.PROBE_REJECT,
            reason,elapsed,m.samples)
        return null
    }

    fun inspect(
        source:File,
        declaredExtension:String,
        traceScope:Int=0,
        isCancelled:()->Boolean={false},
    ):A0AutoPostTransferTechnicalGate.Decoded? {
        check(Looper.myLooper()!=Looper.getMainLooper()) {
            "Audio receipt inspection cannot run on Android main thread"
        }
        val metrics=ProbeMetrics(traceScope,SystemClock.elapsedRealtime())
        A0AcquisitionDiagnostics.emit(traceScope,
            A0AcquisitionDiagnostics.Phase.PROBE_START)
        if(!source.isFile || Files.isSymbolicLink(source.toPath()) ||
            source.length() !in 44L..A0ObservedTransferLimits.MAX_AUDIO_BYTES ||
            isCancelled() || Thread.currentThread().isInterrupted)
            return denied(Failure.INVALID_PRIVATE_SOURCE,metrics)
        val size=source.length()
        val modification=source.lastModified()
        val head=ByteArray(minOf(size.toInt(),HEADER_BYTES))
        val read=try {
            source.inputStream().use { input ->
                var count=0
                while(count<head.size) {
                    if(isCancelled() || Thread.currentThread().isInterrupted)
                        return denied(Failure.SOURCE_CHANGED_OR_REVOKED,metrics)
                    val part=input.read(head,count,head.size-count)
                    if(part<=0)break
                    count+=part
                }
                count
            }
        }catch(_:Exception) {
            // Do not expose transient private file paths or provider errors.
            return denied(Failure.SOURCE_READ_FAILURE,metrics)
        }
        if(read<12)return denied(Failure.HEADER_TOO_SHORT,metrics)
        metrics.step=Step.HEADER
        val header=A0LocalAudioHeaderEvidence.inspect(head.copyOf(read))
            ?:return denied(Failure.UNRECOGNIZED_AUDIO_HEADER,metrics)
        metrics.fixedFlacBlockAvailable=header.flacBlockSize!=null
        metrics.flacTotalSampleCountAvailable=header.flacTotalSamples!=null
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
        if(ext!=expected)return denied(Failure.FILE_EXTENSION_CONFLICT,metrics)

        metrics.step=Step.EXTRACTOR
        val extractor=MediaExtractor()
        val metadata=MediaMetadataRetriever()
        try {
            extractor.setDataSource(source.absolutePath)
            metrics.step=Step.TRACKS
            var audioIndex=-1
            var track:MediaFormat?=null
            for(index in 0 until extractor.trackCount) {
                val format=extractor.getTrackFormat(index)
                val mime=if(format.containsKey(MediaFormat.KEY_MIME))
                    format.getString(MediaFormat.KEY_MIME).orEmpty() else ""
                if(mime.startsWith("video/",ignoreCase=true))
                    return denied(Failure.MULTIPLE_AUDIO_TRACKS,metrics)
                if(mime.startsWith("audio/",ignoreCase=true)) {
                    if(audioIndex>=0)return denied(Failure.MULTIPLE_AUDIO_TRACKS,metrics)
                    audioIndex=index
                    track=format
                }
            }
            if(audioIndex<0 || track==null)
                return denied(Failure.NO_AUDIO_TRACK,metrics)
            val format=track
            val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
            if(!A0LocalAudioHeaderEvidence.acceptsExtractorMime(header.codec,mime))
                return denied(Failure.EXTRACTOR_MIME_CONFLICT,metrics)
            fun longOrNull(key:String):Long? =
                if(format.containsKey(key))runCatching {
                    format.getLong(key)
                }.getOrNull() else null
            fun intOrNull(key:String):Int? =
                if(format.containsKey(key))runCatching {
                    format.getInteger(key)
                }.getOrNull() else null
            metrics.step=Step.DURATION
            metadata.setDataSource(source.absolutePath)
            val extractorDuration=longOrNull(MediaFormat.KEY_DURATION)
                ?.takeIf {it>0L}?.div(1000L)
            val retrieverDuration=metadata.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf {it>0L}
            if(extractorDuration!=null && retrieverDuration!=null &&
                abs(extractorDuration-retrieverDuration)>2_000L)
                return denied(Failure.DISAGREEING_DURATIONS,metrics)
            val duration=retrieverDuration ?: extractorDuration
                ?:return denied(Failure.DURATION_UNAVAILABLE,metrics)
            if(duration !in 15_000L..7_200_000L)
                return denied(Failure.INVALID_DURATION,metrics)

            val fromExtractor=intOrNull(MediaFormat.KEY_SAMPLE_RATE)
            if(header.sampleRateHz!=null && fromExtractor!=null &&
                header.sampleRateHz!=fromExtractor)
                return denied(Failure.SAMPLE_RATE_CONFLICT,metrics)
            val sampleRate=header.sampleRateHz ?: fromExtractor
            val trackRate=intOrNull(MediaFormat.KEY_BIT_RATE)
                ?.takeIf {it>0}?.div(1000)
            val metadataRate=metadata.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()?.takeIf {it>0}?.div(1000)
            val extractorBitrate=trackRate ?: metadataRate

            metrics.step=Step.SCAN
            extractor.selectTrack(audioIndex)
            val input=ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES)
            val endBy=SystemClock.elapsedRealtime()+MAX_SCAN_MILLIS
            var samples=0
            var emptyLeading=0
            var bytes=0L
            var lastUs=-1L
            var previousUs=-1L
            var toleratedTimestampBacksteps=0
            var largeFlacTimestampResets=0
            var maximumFlacResetMillis=0L
            var reachedEos=false
            while(samples<MAX_SAMPLES &&
                SystemClock.elapsedRealtime()<endBy &&
                !isCancelled() && !Thread.currentThread().isInterrupted) {
                metrics.samples=samples
                input.clear()
                val amount=extractor.readSampleData(input,0)
                if(amount<0) {
                    reachedEos=true
                    break
                }
                // A zero-size packet is not permission to ignore its crypto
                // flags or an unexpected source track.
                if((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)
                    return denied(Failure.ENCRYPTED_SAMPLE,metrics)
                val reportedTrack=extractor.sampleTrackIndex
                if(amount==0) {
                    if(reportedTrack!=audioIndex && reportedTrack!=-1)
                        return denied(Failure.WRONG_SAMPLE_TRACK,metrics)
                    // A few extractors emit empty codec/config packets
                    // before the first positive frame. Skip at most two;
                    // never ignore empty gaps within real audio. All actual
                    // samples and the end timestamp must still be checked.
                    val more=extractor.advance()
                    if(A0AudioSampleScanPolicy.skipLeadingEmpty(
                            samples,emptyLeading,reportedTrack==audioIndex,more)) {
                        emptyLeading++
                        continue
                    }
                    if(A0AudioSampleScanPolicy.acceptTerminalEmpty(
                            samples,lastUs,duration,!more)) {
                        reachedEos=true
                        break
                    }
                    return denied(Failure.ZERO_LENGTH_SAMPLE,metrics)
                }
                if(reportedTrack!=audioIndex)
                    return denied(Failure.WRONG_SAMPLE_TRACK,metrics)
                val timestamp=extractor.sampleTime
                val flac=header.codec==A0CanonicalAcquisitionIntent.Codec.FLAC
                // Compare adjacent presentations, NOT with the highest
                // timestamp: after a device reset, subsequent increasing
                // samples must not each count as another regression.
                when(A0AudioSampleScanPolicy.timestampVerdict(
                    timestamp,previousUs,toleratedTimestampBacksteps,flac)) {
                    A0AudioSampleScanPolicy.TimestampVerdict.NEGATIVE ->
                        return denied(Failure.NEGATIVE_SAMPLE_TIME,metrics)
                    A0AudioSampleScanPolicy.TimestampVerdict.REGRESSION -> {
                        val delta=(previousUs-timestamp).coerceAtLeast(0L)/1000L
                        metrics.backstepMillis=delta
                        if(!flac)return denied(Failure.REGRESSING_SAMPLE_TIME,metrics)
                        largeFlacTimestampResets++
                        maximumFlacResetMillis=maxOf(maximumFlacResetMillis,delta)
                        metrics.resetEvents=largeFlacTimestampResets
                        // A suspicious PTS is NEVER proof of file integrity.
                        // Only the independent STREAMINFO + complete demux
                        // reconciliation below may authorize finishing.
                    }
                    A0AudioSampleScanPolicy.TimestampVerdict.TOLERATE_SINGLE_SMALL_FLAC_BACKSTEP -> {
                        toleratedTimestampBacksteps++
                        metrics.toleratedBacksteps=toleratedTimestampBacksteps
                    }
                    A0AudioSampleScanPolicy.TimestampVerdict.OK -> Unit
                }
                previousUs=timestamp
                lastUs=maxOf(lastUs,timestamp)
                bytes+=amount
                if(bytes>A0ObservedTransferLimits.MAX_AUDIO_BYTES)
                    return denied(Failure.SAMPLE_BYTE_LIMIT_EXCEEDED,metrics)
                samples++
                if(!extractor.advance()) {
                    reachedEos=true
                    break
                }
            }
            metrics.samples=samples
            metrics.step=Step.FINAL
            metrics.endGapMillis=if(lastUs>=0)
                abs(duration-lastUs/1000L) else -1L
            val timeline=A0FlacTimelineRecoveryPolicy.assess(
                resetEvents=largeFlacTimestampResets,
                maximumRegressionMs=maximumFlacResetMillis,
                fixedBlockSize=header.flacBlockSize,
                totalPcmSamples=header.flacTotalSamples,
                sampleRateHz=header.sampleRateHz,
                durationMillis=duration,
                positiveDemuxPackets=samples,
                demuxedBytes=bytes,
                fileBytes=size)
            metrics.timelineVerdict=timeline
            // STREAMINFO does not substitute for the actual demux walk:
            // strict EOS, full packet count, encoded bytes and duration
            // corroboration are required before discarding broken PTS.
            val recoveryAllowed=timeline==
                A0FlacTimelineRecoveryPolicy.Verdict.VERIFIED_FIXED_BLOCK_COUNT
            if(largeFlacTimestampResets>0 && !recoveryAllowed)
                return denied(Failure.FLAC_TIMELINE_UNVERIFIED,metrics)
            val endTimestamp=if(recoveryAllowed)duration*1000L else lastUs
            val termination=A0ProbeTermination.classify(
                reachedEos,samples,endTimestamp,duration,
                capReached=samples>=MAX_SAMPLES,
                timeExpired=SystemClock.elapsedRealtime()>=endBy,
                cancelled=isCancelled() || Thread.currentThread().isInterrupted)
            metrics.termination=termination
            if(termination!=A0ProbeTermination.Reason.COMPLETE)
                return denied(Failure.INCOMPLETE_SAMPLE_SCAN,metrics)
            if(source.length()!=size || source.lastModified()!=modification ||
                isCancelled() || Thread.currentThread().isInterrupted)
                return denied(Failure.SOURCE_CHANGED_OR_REVOKED,metrics)
            if(recoveryAllowed)
                runCatching {
                    Log.d("BitChord",
                        "soulseek_audio_probe accepted=FLAC_STREAMINFO_RECONCILED" +
                            " reset_events=${metrics.resetEvents}" +
                            " samples=${samples.coerceIn(0,400000)}" +
                            " scope=$traceScope")
                }
            else if(toleratedTimestampBacksteps>0)
                runCatching {
                    Log.d("BitChord","soulseek_audio_probe accepted=SMALL_FLAC_BACKSTEP")
                }
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
            A0AcquisitionDiagnostics.emit(traceScope,
                A0AcquisitionDiagnostics.Phase.PROBE_ACCEPT,
                reason=header.codec,
                elapsedMillis=SystemClock.elapsedRealtime()-metrics.start,
                count=samples)
            return A0AutoPostTransferTechnicalGate.Decoded(
                codec=header.codec,extension=ext,durationMillis=duration,
                bitrateKbps=bitrate,sampleRateHz=sampleRate,
                bitDepth=header.bitDepth,completed=true)
        }catch(error:Exception) {
            metrics.fault=A0AcquisitionDiagnostics.fault(error)
            return denied(Failure.MEDIA_EXTRACTOR_EXCEPTION,metrics)
        }finally {
            runCatching {metadata.release()}
            runCatching {extractor.release()}
        }
    }
}
