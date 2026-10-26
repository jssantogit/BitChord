package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import java.util.Locale

/**
 * Read-only re-screening of observed Soulseek offers against EVERY slot of
 * a requested release. A track matched as part of a shared folder is NOT proof
 * that the peer offers the exact edition/master; all evidence remains untrusted.
 *
 * Bounded CPU: at most 8192 track/offer assessments, on an existing background
 * discovery worker after P readers have relinquished the server connection.
 * Does not perform a new peer search or start a transfer.
 */
internal object A0ReleaseDiscoveryShadowAdapter {
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
        for(entry in entries) {
            val expectedArtist=entry.query.split(
                Regex("\\s+[-–—]\\s+"),limit=2).firstOrNull()
            for(candidate in choices.take(sourceCap)) {
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
                // Avoid expensive detailed screening for unrelated titles,
                // but preserve all offers in the manual candidate UI.
                if(ranked.match!=A0CandidateRank.Match.ALL_TERMS)continue
                val preflight=A0CandidatePreflight.assess(
                    entry.query,ranked,rate,duration,entry.durationSeconds,
                    ext,candidate.sizeBytes,sample,depth)
                val immediate=path.replace('\\','/')
                    .substringBeforeLast('/',"").substringAfterLast('/',"")
                val album=A0CanonicalAlbumPolicy.assess(
                    A0CanonicalAlbumPolicy.Target(
                        requestedAlbum=entry.album,expectedArtist=expectedArtist),
                    A0CanonicalAlbumPolicy.Claim(
                        immediate,A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT))
                val codec=when(ext) {
                    "mp3" -> A0CanonicalAcquisitionIntent.Codec.MP3
                    "aac" -> A0CanonicalAcquisitionIntent.Codec.AAC_ADTS
                    "flac" -> A0CanonicalAcquisitionIntent.Codec.FLAC
                    "wav" -> A0CanonicalAcquisitionIntent.Codec.PCM_WAV
                    else -> null
                }
                val qualityClaim=A0DownloadQualityPolicy.evaluate(quality,
                    A0DownloadQualityPolicy.Offer(codec,ext,rate,sample,depth),
                    setOf(A0CanonicalAcquisitionIntent.Codec.MP3,
                        A0CanonicalAcquisitionIntent.Codec.FLAC,
                        A0CanonicalAcquisitionIntent.Codec.PCM_WAV,
                        A0CanonicalAcquisitionIntent.Codec.AAC_ADTS))
                val claimKey=buildString {
                    append(name.lowercase(Locale.ROOT)).append('|')
                    append(ext).append('|').append(candidate.sizeBytes).append('|')
                    append(rate).append('|').append(duration).append('|')
                    append(sample).append('|').append(depth)
                }
                observations.add(A0ReleaseDiscoveryPlanner.Offer(
                    slotId=entry.trackId,peerKey=candidate.peerUsername,
                    remotePath=path,claimKey=claimKey,score=ranked.score,
                    declaredDurationSeconds=duration,
                    preflight=preflight.confidence,
                    qualityCompatible=qualityClaim.status in setOf(
                        A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED,
                        A0DownloadQualityPolicy.Status.NOT_CONSTRAINED),
                    albumCompatible=album.status in setOf(
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
