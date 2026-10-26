package com.music.bitchord.acquisition.a0

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Offline counterfactual of release-oriented Soulseek discovery.
 *
 * A matching peer folder can supply candidates for many release tracks, but
 * neither a folder name nor a repeated file claim authenticates the recording.
 * This planner has NO P/F, account, storage, network or transfer capability.
 * Callers must assess untrusted metadata for EACH canonical track separately.
 */
internal object A0ReleaseDiscoveryPlanner {
    const val MAX_TRACKS=64
    const val MAX_OFFERS=8192
    const val MAX_ALTERNATIVES=3

    data class Target(val id:String,val expectedDurationSeconds:Int?)

    class Offer(
        val slotId:String,
        val peerKey:String,
        val remotePath:String,
        val claimKey:String,
        val score:Int,
        val declaredDurationSeconds:Int?,
        val preflight:A0CandidatePreflight.Confidence,
        val qualityCompatible:Boolean,
        val albumCompatible:Boolean,
        val identityCompatible:Boolean,
        val versionCompatible:Boolean,
        val availability:A0PeerAvailability.Tier,
    ) {
        override fun toString()="ReleaseOffer(redacted)"
    }

    enum class Status { SUGGESTED, AMBIGUOUS, REVIEW }
    class TrackDecision(
        val slotId:String,
        val status:Status,
        val alternativePeers:List<String>,
    ) {
        override fun toString()="TrackDecision(redacted)"
    }
    class Preview(
        val autoReadyTracks:Int,
        val manualReviewTracks:Int,
        val ambiguousTracks:Int,
        val bestFolderCoverage:Int,
        val foldersConsidered:Int,
        val examinedOffers:Int,
        val trackDecisions:List<TrackDecision>,
    ) {
        val canTransfer:Boolean get()=false
        override fun toString()="ReleasePreview(aggregated)"
    }

    private fun safeFolder(path:String):String? {
        if(path.length !in 3..2048 || path.any {
                it.isISOControl() || it in '\u202A'..'\u202E' ||
                it in '\u2066'..'\u2069' || it in '\u200B'..'\u200F' ||
                it=='\u2060' || it=='\uFEFF'
            })return null
        val parts=path.replace('\\','/').split('/')
        if(parts.size<2 || parts.any { it==".." || it=="." })return null
        val folder=parts.dropLast(1).joinToString("/").trim('/')
        return Normalizer.normalize(folder,Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }

    fun preview(
        targets:List<Target>,
        offers:List<Offer>,
        evidence:A0PeerReliabilityEvidence=A0PeerReliabilityEvidence(),
    ):Preview {
        require(targets.size in 1..MAX_TRACKS)
        val ids=targets.map { it.id }
        val targetById=targets.associateBy { it.id }
        // Duplicate canonical slots, even accidentally, must not be
        // interpreted as independent coverage or repeated authorization.
        val invalidTargets=ids.distinct().size!=ids.size ||
            targets.any { it.id.isBlank() || it.id.length>180 }
        if(invalidTargets) return Preview(0,targets.size,0,0,0,0,
            targets.map { TrackDecision(it.id,Status.REVIEW,emptyList()) })

        data class Selected(val value:Offer,val folder:String)
        val eligible=offers.take(MAX_OFFERS).mapNotNull { offer ->
            val target=targetById[offer.slotId] ?: return@mapNotNull null
            val expected=target.expectedDurationSeconds?.takeIf { it in 15..7200 }
                ?: return@mapNotNull null
            val reported=offer.declaredDurationSeconds?.takeIf { it in 15..7200 }
                ?: return@mapNotNull null
            if(abs(expected-reported)>8 || offer.score<65 ||
                offer.preflight!=A0CandidatePreflight.Confidence.STRONG ||
                !offer.qualityCompatible || !offer.albumCompatible ||
                !offer.identityCompatible || !offer.versionCompatible ||
                offer.availability==A0PeerAvailability.Tier.UNAVAILABLE ||
                offer.peerKey.isBlank() || offer.peerKey.length>256 ||
                offer.claimKey.isBlank() || offer.claimKey.length>512) return@mapNotNull null
            val folder=safeFolder(offer.remotePath) ?: return@mapNotNull null
            Selected(offer,folder)
        }

        // Source folder coverage is a discovery scheduling hint, not proof of
        // album edition, mastering or permission to download anything.
        val folders=eligible.groupBy { it.value.peerKey to it.folder }
        val bestCoverage=folders.values.maxOfOrNull {
            group -> group.map { it.value.slotId }.distinct().size
        } ?: 0

        var ambiguous=0
        val decisions=targets.map { target ->
            val sameTrack=eligible.filter { it.value.slotId==target.id }
            val claimGroups=sameTrack.groupBy { it.value.claimKey }
            val leaders=claimGroups.values.mapNotNull { group ->
                group.maxByOrNull { it.value.score }
            }.sortedByDescending { it.value.score }
            val decision=when {
                leaders.isEmpty() -> TrackDecision(target.id,Status.REVIEW,emptyList())
                leaders.size>1 && leaders[0].value.score-leaders[1].value.score<12 -> {
                    ambiguous++
                    TrackDecision(target.id,Status.AMBIGUOUS,emptyList())
                }
                else -> {
                    val winning=leaders[0].value.claimKey
                    val alternate=evidence.sequentialFallbacks(
                        sameTrack.filter { it.value.claimKey==winning }.map {
                            A0PeerReliabilityEvidence.Alternative(
                                it.value.peerKey,it.value.claimKey,it.value.availability)
                        },
                        claimKey=winning,failedPeers=emptySet(),
                        maxPeers=MAX_ALTERNATIVES)
                    TrackDecision(target.id,Status.SUGGESTED,alternate)
                }
            }
            decision
        }
        val ready=decisions.count { it.status==Status.SUGGESTED }
        return Preview(ready,targets.size-ready,ambiguous,bestCoverage,
            folders.size,offers.size.coerceAtMost(MAX_OFFERS),decisions)
    }
}
