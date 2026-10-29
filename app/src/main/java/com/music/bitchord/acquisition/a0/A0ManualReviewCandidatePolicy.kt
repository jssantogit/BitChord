package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import java.util.Locale

/**
 * Bounded, volatile presentation of the *existing* search results. This is
 * never an unattended selection or permission to contact a Soulseek peer.
 * A human must confirm one candidate; transport + decoder + A3 gates remain
 * independent. Do not serialize, log or pass source handles through Intent.
 */
internal object A0ManualReviewCandidatePolicy {
    private const val MAX_RETAINED=16
    private val supported=setOf(
        A0CanonicalAcquisitionIntent.Codec.MP3,
        A0CanonicalAcquisitionIntent.Codec.FLAC,
        A0CanonicalAcquisitionIntent.Codec.PCM_WAV,
        A0CanonicalAcquisitionIntent.Codec.AAC_ADTS,
    )

    private fun codec(format:String)=when(format.lowercase(Locale.ROOT)) {
        "mp3" -> A0CanonicalAcquisitionIntent.Codec.MP3
        "flac" -> A0CanonicalAcquisitionIntent.Codec.FLAC
        "wav" -> A0CanonicalAcquisitionIntent.Codec.PCM_WAV
        "aac" -> A0CanonicalAcquisitionIntent.Codec.AAC_ADTS
        else -> null
    }

    fun retain(
        entry:SoulseekBatchPlan.Entry,
        observed:List<A0ObservedCandidateHandoff.Choice>,
        profile:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
    ):List<A0ObservedCandidateHandoff.Choice> {
        if(entry.durationSeconds?.takeIf { it in 15..7200 }==null)return emptyList()
        return observed.asSequence().mapNotNull { choice ->
            val claim=choice.claim
            val ext=claim.format.lowercase(Locale.ROOT)
            val rank=A0CandidateRank.rank(entry.query,claim.filename,
                claim.bitrateKbps,remotePath=choice.remotePath,format=ext,
                declaredDurationSeconds=claim.durationSeconds,
                expectedDurationSeconds=entry.durationSeconds,
                declaredSampleRateHz=claim.sampleRateHz,
                declaredBitDepth=claim.bitDepth,sizeBytes=claim.sizeBytes)
            if(rank.match!=A0CandidateRank.Match.ALL_TERMS ||
                rank.score<65 ||
                A0CandidateRank.Warning.ARTIST_CONFLICT in rank.warnings ||
                A0CandidateRank.Warning.VERSION_MISMATCH in rank.warnings ||
                A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in rank.warnings ||
                !A0AutoTransportAdmission.accepts(choice))
                return@mapNotNull null
            val risk=A0CandidatePreflight.assess(entry.query,rank,
                claim.bitrateKbps,claim.durationSeconds,entry.durationSeconds,
                ext,claim.sizeBytes,claim.sampleRateHz,claim.bitDepth)
            if(risk.confidence==A0CandidatePreflight.Confidence.UNSAFE)
                return@mapNotNull null
            val quality=A0DownloadQualityPolicy.evaluate(profile,
                A0DownloadQualityPolicy.Offer(codec(ext),ext,
                    claim.bitrateKbps,claim.sampleRateHz,claim.bitDepth),
                supported)
            // Missing peer bitrate/bit-depth must not hide a potentially
            // usable source from HUMAN review. Only a clearly incompatible
            // profile is excluded. The real received bytes must still pass
            // the independent decoder/quality gate before A3 publication.
            val uncertainButInspectable=
                quality.status==A0DownloadQualityPolicy.Status.INCONCLUSIVE_MANUAL &&
                quality.reason==A0DownloadQualityPolicy.Reason.INSUFFICIENT_TECHNICAL_CLAIMS
            if(quality.status !in setOf(
                A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED,
                A0DownloadQualityPolicy.Status.NOT_CONSTRAINED) &&
                !uncertainButInspectable)
                return@mapNotNull null
            // A directory hint must never be treated as an authenticated
            // album identity. A composite/inconclusive folder is shown for
            // human verification, never auto-promoted.
            val folder=choice.remotePath.replace('\\','/')
                .substringBeforeLast('/').substringAfterLast('/')
            val album=A0CanonicalAlbumPolicy.assess(
                A0CanonicalAlbumPolicy.Target(entry.album,
                    expectedArtist=entry.query.substringBefore(" - ")),
                A0CanonicalAlbumPolicy.Claim(
                    folder,A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT))
            if(album.status==A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM)
                return@mapNotNull null
            rank.score to choice
        }.distinctBy { it.second.peerUsername to it.second.remotePath }
            .sortedWith(compareByDescending<Pair<Int,A0ObservedCandidateHandoff.Choice>> {
                it.first
            }.thenBy { it.second.displayName })
            .take(MAX_RETAINED).map { it.second }.toList()
    }

    override fun toString()="A0ManualReviewCandidatePolicy(redacted)"
}
