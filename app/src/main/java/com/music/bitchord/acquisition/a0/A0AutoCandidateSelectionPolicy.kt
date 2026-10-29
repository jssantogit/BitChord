package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Per-canonical-slot candidate decision over the SAME observed offers used
 * by the manual selector. Every sibling track is rescored independently:
 * the preflight attached to a Choice belongs to its ORIGINAL search, and
 * must never be recycled as proof for another song in a release.
 *
 * This class is a read-only recommendation, not an automatic P/F instruction.
 * Paths, peers and file names never enter its public diagnostics/toString.
 */
internal object A0AutoCandidateSelectionPolicy {
    enum class Status { PROVISIONAL, MANUAL_REVIEW }
    enum class Reason {
        INVALID_TARGET, INCOMPLETE_SAMPLE, CONTRADICTORY_SOURCE,
        NO_STRONG_CANDIDATE, AMBIGUOUS_EDITION, NO_READY_SOURCE,
    }
    class Decision(
        val status:Status,
        val reason:Reason?,
        /** Internal read-only handles, equivalent declared metadata only. */
        val sequentialAlternatives:List<A0ObservedCandidateHandoff.Choice>,
        val examined:Int,
        val eligible:Int,
    ) {
        val canTransfer:Boolean get()=false
        override fun toString()="AutoCandidateDecision(status=$status, reason=$reason)"
    }
    private fun review(reason:Reason,examined:Int=0,eligible:Int=0)=
        Decision(Status.MANUAL_REVIEW,reason,emptyList(),examined,eligible)

    private data class Eligible(
        val choice:A0ObservedCandidateHandoff.Choice,
        val rank:Int,
    )

    /**
     * One peer may write "01 Artist - Title.flac", while another uses
     * "Artist - Title.flac". ONLY this numbering difference may be ignored:
     * identical advertised byte size, codec, bit properties and duration
     * are mandatory. This does NOT authenticate the recording/master.
     * Different explicit track numbers (01 vs 02) remain distinct.
     */
    private fun sameNumberedTrackClaim(
        a:A0CandidateClaimClusters.Claim,
        b:A0CandidateClaimClusters.Claim,
    ):Boolean {
        if(a.format!=b.format || a.sizeBytes!=b.sizeBytes ||
            a.bitrateKbps!=b.bitrateKbps ||
            a.durationSeconds!=b.durationSeconds ||
            a.sampleRateHz!=b.sampleRateHz ||
            a.bitDepth!=b.bitDepth)return false
        val number=Regex("^([0-9]{1,3})[ ._-]+")
        fun label(name:String):Pair<String?,String> {
            val normalized=Normalizer.normalize(name,Normalizer.Form.NFKC)
                .trim().lowercase(Locale.ROOT)
            val match=number.find(normalized)
            val prefix=match?.groupValues?.get(1)
            return prefix to if(match!=null)normalized.substring(match.value.length)
                else normalized
        }
        val first=label(a.filename)
        val second=label(b.filename)
        return first.second==second.second &&
            (first.first==second.first ||
                first.first==null || second.first==null)
    }
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

    fun preview(
        target:SoulseekBatchPlan.Entry,
        observed:List<A0ObservedCandidateHandoff.Choice>,
        profile:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        reliability:A0PeerReliabilityEvidence=A0PeerReliabilityEvidence(),
        sampleTruncated:Boolean=false,
    ):Decision {
        val parts=target.query.split(Regex("\\s+[-–—]\\s+"),limit=2)
        val expected=target.durationSeconds
        if(target.trackId.isBlank() || target.trackId.length>180 ||
            parts.size!=2 || parts.any { it.isBlank() } ||
            A0ReleaseDiscoveryShadowAdapter.titleTokens(target.query).isEmpty() ||
            expected==null || expected !in 15..7200)
            return review(Reason.INVALID_TARGET)
        // The collector and multi-search cache are both bounded. Silently
        // ignoring the tail could turn an ambiguous population into one winner.
        if(sampleTruncated || observed.size>512)
            return review(Reason.INCOMPLETE_SAMPLE,observed.size)
        val seen=HashMap<Pair<String,String>,A0CandidateClaimClusters.Claim>()
        for(choice in observed) {
            val id=choice.peerUsername to choice.remotePath
            val previous=seen.putIfAbsent(id,choice.claim)
            if(previous!=null && !A0CandidateClaimClusters.sameClaim(
                    previous,choice.claim))
                return review(Reason.CONTRADICTORY_SOURCE,observed.size)
        }
        val artist=parts[0]
        val strong=observed.mapNotNull { choice ->
            val claim=choice.claim
            val ext=claim.format.lowercase(Locale.ROOT)
            val ranked=A0CandidateRank.rank(
                target.query,claim.filename,claim.bitrateKbps,
                remotePath=choice.remotePath,format=ext,
                declaredDurationSeconds=claim.durationSeconds,
                expectedDurationSeconds=expected,
                declaredSampleRateHz=claim.sampleRateHz,
                declaredBitDepth=claim.bitDepth,
                sizeBytes=claim.sizeBytes
            )
            if(ranked.match!=A0CandidateRank.Match.ALL_TERMS ||
                ranked.score<65 ||
                A0CandidateRank.Warning.ARTIST_CONFLICT in ranked.warnings ||
                A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in ranked.warnings ||
                A0CandidateRank.Warning.VERSION_MISMATCH in ranked.warnings)
                return@mapNotNull null
            val preflight=A0CandidatePreflight.assess(
                target.query,ranked,claim.bitrateKbps,
                claim.durationSeconds,expected,ext,claim.sizeBytes,
                claim.sampleRateHz,claim.bitDepth)
            if(preflight.confidence!=A0CandidatePreflight.Confidence.STRONG)
                return@mapNotNull null
            val directory=choice.remotePath.replace('\\','/')
                .substringBeforeLast('/',"")
            val folder=directory.substringAfterLast('/',"")
            val albumTarget=A0CanonicalAlbumPolicy.Target(
                target.album,expectedArtist=artist)
            fun assessFolder(name:String)=A0CanonicalAlbumPolicy.assess(
                albumTarget,A0CanonicalAlbumPolicy.Claim(name,
                    A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT))
            val nearest=assessFolder(folder)
            // Only one level of generic audio-format/disc folders may be
            // ignored, and ONLY when the immediate parent names the exact
            // requested release. Never promote an unrelated album, an
            // artist-only folder or a composite/remastered edition.
            val album=if(nearest.reason==
                A0CanonicalAlbumPolicy.Reason.ALBUM_HINT_GENERIC &&
                '/' in directory) {
                assessFolder(directory.substringBeforeLast('/').substringAfterLast('/'))
            }else nearest
            if(album.status !in setOf(
                    A0CanonicalAlbumPolicy.Status.UNCONSTRAINED,
                    A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED))
                return@mapNotNull null
            val quality=A0DownloadQualityPolicy.evaluate(
                profile,
                A0DownloadQualityPolicy.Offer(
                    codec(ext),ext,claim.bitrateKbps,
                    claim.sampleRateHz,claim.bitDepth),
                supported)
            if(quality.status!=
                A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED)
                return@mapNotNull null
            Eligible(choice,ranked.score)
        }
        if(strong.isEmpty())return review(
            Reason.NO_STRONG_CANDIDATE,observed.size)
        // Exact equality of peer claims is not proof of actual equal audio.
        // It only limits which alternate connections may be considered.
        val clusters=mutableListOf<MutableList<Eligible>>()
        for(candidate in strong) {
            val group=clusters.firstOrNull {
                A0CandidateClaimClusters.sameClaim(
                    it.first().choice.claim,candidate.choice.claim) ||
                    sameNumberedTrackClaim(
                        it.first().choice.claim,candidate.choice.claim)
            }
            if(group==null)clusters.add(mutableListOf(candidate))
            else group.add(candidate)
        }
        val rankedGroups=clusters.sortedWith(
            compareByDescending<List<Eligible>> { it.maxOf { e -> e.rank } }
                .thenBy { it.first().choice.claim.filename.lowercase(Locale.ROOT) })
        if(rankedGroups.size>1 &&
            rankedGroups[0].maxOf { it.rank } -
                rankedGroups[1].maxOf { it.rank }<12)
            return review(Reason.AMBIGUOUS_EDITION,observed.size,strong.size)
        val leading=rankedGroups[0]
        // Only a peer claiming a currently free slot with acceptable queue/
        // speed evidence may be provisionally preferred for unattended use.
        val ready=leading.filter {
            it.choice.peerAvailability.tier==A0PeerAvailability.Tier.READY
        }
        if(ready.isEmpty())
            return review(Reason.NO_READY_SOURCE,observed.size,strong.size)
        val ordered=reliability.sequentialFallbacks(
            ready.map { A0PeerReliabilityEvidence.Alternative(
                it.choice.peerUsername,"same-claim",it.choice.peerAvailability.tier) },
            claimKey="same-claim",failedPeers=emptySet(),maxPeers=3)
        val handles=ordered.mapNotNull { peer ->
            ready.firstOrNull { it.choice.peerUsername==peer }?.choice
        }
        if(handles.isEmpty())
            return review(Reason.NO_READY_SOURCE,observed.size,strong.size)
        return Decision(Status.PROVISIONAL,null,handles,observed.size,strong.size)
    }
}
