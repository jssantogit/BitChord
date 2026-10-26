package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import java.util.Locale

/**
 * Read-only re-screening of observed Soulseek offers against EVERY slot of
 * a requested release. A track matched as part of a shared folder is NOT proof
 * that the peer offers the exact edition/master; all evidence remains untrusted.
 *
 * Bounded CPU: scan at most 512 memory-only observations per slot for a cheap
 * match/quality/album ranking, and perform at most 8192 full preflight
 * assessments in the existing background discovery worker. Unlike taking the
 * first N observations, this does not silently exclude a late relevant track.
 * Does not perform a new peer search or start a transfer.
 */
internal object A0ReleaseDiscoveryShadowAdapter {
    private const val MAX_OBSERVED_CHOICES=512
    private data class Ranked(
        val candidate:A0ObservedCandidateHandoff.Choice,
        val ranking:A0CandidateRank.Ranking,
        val quality:A0DownloadQualityPolicy.Assessment,
        val album:A0CanonicalAlbumPolicy.Assessment,
    ) {
        val eligibleForFullAssessment:Boolean get() =
            quality.status in setOf(
                A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED,
                A0DownloadQualityPolicy.Status.NOT_CONSTRAINED) &&
                album.status in setOf(
                    A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,
                    A0CanonicalAlbumPolicy.Status.UNCONSTRAINED) &&
                A0CandidateRank.Warning.ARTIST_CONFLICT !in ranking.warnings &&
                A0CandidateRank.Warning.VERSION_MISMATCH !in ranking.warnings &&
                A0CandidateRank.Warning.IDENTITY_AMBIGUOUS !in ranking.warnings
    }

    fun preview(
        entries:List<SoulseekBatchPlan.Entry>,
        choices:List<A0ObservedCandidateHandoff.Choice>,
        quality:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        evidence:A0PeerReliabilityEvidence=A0PeerReliabilityEvidence(),
    ):A0ReleaseDiscoveryPlanner.Preview {
        require(entries.size in 1..SoulseekBatchPlan.MAX_TRACKS)
        val sourceCap=minOf(192,A0ReleaseDiscoveryPlanner.MAX_OFFERS/entries.size)
        val observations=ArrayList<A0ReleaseDiscoveryPlanner.Offer>(
            sourceCap*entries.size)
        val supportedCodecs=setOf(A0CanonicalAcquisitionIntent.Codec.MP3,
            A0CanonicalAcquisitionIntent.Codec.FLAC,
            A0CanonicalAcquisitionIntent.Codec.PCM_WAV,
            A0CanonicalAcquisitionIntent.Codec.AAC_ADTS)
        for(entry in entries) {
            val expectedArtist=entry.query.split(
                Regex("\\s+[-–—]\\s+"),limit=2).firstOrNull()
            // Score the entire bounded release shadow, not the first 128/192
            // offers that happened to arrive. Those early offers may belong
            // to other songs or advertise the wrong quality/album.
            val shortlisted=choices.asSequence().take(MAX_OBSERVED_CHOICES)
                .mapNotNull { candidate ->
                    val name=candidate.claim.filename
                    val path=candidate.remotePath
                    val ext=candidate.claim.format.lowercase(Locale.ROOT)
                    val rate=candidate.claim.bitrateKbps
                    val duration=candidate.claim.durationSeconds
                    val sample=candidate.claim.sampleRateHz
                    val depth=candidate.claim.bitDepth
                    val ranked=A0CandidateRank.rank(
                        entry.query,name,rate,remotePath=path,format=ext,
                        declaredDurationSeconds=duration,
                        expectedDurationSeconds=entry.durationSeconds,
                        declaredSampleRateHz=sample,declaredBitDepth=depth,
                        sizeBytes=candidate.sizeBytes)
                    if(ranked.match!=A0CandidateRank.Match.ALL_TERMS)
                        return@mapNotNull null
                    val immediate=path.replace('\\','/')
                        .substringBeforeLast('/',"").substringAfterLast('/',"")
                    val album=A0CanonicalAlbumPolicy.assess(
                        A0CanonicalAlbumPolicy.Target(
                            requestedAlbum=entry.album,expectedArtist=expectedArtist),
                        A0CanonicalAlbumPolicy.Claim(immediate,
                            A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT))
                    val codec=when(ext) {
                        "mp3" -> A0CanonicalAcquisitionIntent.Codec.MP3
                        "aac" -> A0CanonicalAcquisitionIntent.Codec.AAC_ADTS
                        "flac" -> A0CanonicalAcquisitionIntent.Codec.FLAC
                        "wav" -> A0CanonicalAcquisitionIntent.Codec.PCM_WAV
                        else -> null
                    }
                    val qualityClaim=A0DownloadQualityPolicy.evaluate(quality,
                        A0DownloadQualityPolicy.Offer(codec,ext,rate,sample,depth),
                        supportedCodecs)
                    Ranked(candidate,ranked,qualityClaim,album)
                }.sortedWith(compareByDescending<Ranked> {
                    it.eligibleForFullAssessment
                }.thenByDescending { it.ranking.score }
                    .thenBy { it.candidate.peerUsername }
                    .thenBy { it.candidate.remotePath })
                .take(sourceCap).toList()
            for(observation in shortlisted) {
                val candidate=observation.candidate
                val ranked=observation.ranking
                val ext=candidate.claim.format.lowercase(Locale.ROOT)
                val rate=candidate.claim.bitrateKbps
                val duration=candidate.claim.durationSeconds
                val sample=candidate.claim.sampleRateHz
                val depth=candidate.claim.bitDepth
                val preflight=A0CandidatePreflight.assess(
                    entry.query,ranked,rate,duration,entry.durationSeconds,
                    ext,candidate.sizeBytes,sample,depth)
                val claimKey=buildString {
                    append(candidate.claim.filename.lowercase(Locale.ROOT)).append('|')
                    append(ext).append('|').append(candidate.sizeBytes).append('|')
                    append(rate).append('|').append(duration).append('|')
                    append(sample).append('|').append(depth)
                }
                observations.add(A0ReleaseDiscoveryPlanner.Offer(
                    slotId=entry.trackId,peerKey=candidate.peerUsername,
                    remotePath=candidate.remotePath,claimKey=claimKey,score=ranked.score,
                    declaredDurationSeconds=duration,
                    preflight=preflight.confidence,
                    qualityCompatible=observation.quality.status in setOf(
                        A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED,
                        A0DownloadQualityPolicy.Status.NOT_CONSTRAINED),
                    albumCompatible=observation.album.status in setOf(
                        A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,
                        A0CanonicalAlbumPolicy.Status.UNCONSTRAINED),
                    identityCompatible=A0CandidateRank.Warning.ARTIST_CONFLICT
                        !in ranked.warnings &&
                        A0CandidateRank.Warning.IDENTITY_AMBIGUOUS !in ranked.warnings,
                    versionCompatible=A0CandidateRank.Warning.VERSION_MISMATCH
                        !in ranked.warnings,
                    availability=candidate.peerAvailability.tier,
                ))
            }
        }
        return SoulseekBatchPlan.previewDiscovery(entries,observations,evidence)
    }
}
