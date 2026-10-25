package com.music.bitchord.acquisition.a0

import java.text.Normalizer
import java.util.Locale

/**
 * Groups strictly equal metadata declarations, never actual audio identity.
 * Two peers can publish different bytes with the same apparent file signature.
 * This class ONLY supports reporting and counterfactual manual UI hints.
 */
internal object A0CandidateClaimClusters {
    data class Claim(
        val filename:String,
        val format:String,
        val sizeBytes:Long,
        val bitrateKbps:Int?,
        val durationSeconds:Int?,
        val sampleRateHz:Int?,
        val bitDepth:Int?,
    ) {
        // Metadata may contain the user's query and a remote filename. Never
        // include raw claim material in diagnostics or accidental exceptions.
        override fun toString()="A0Claim(redacted)"
    }

    data class Entry(
        val claim:Claim,
        val assessment:A0CandidatePreflight.Assessment,
        val score:Int,
        val availability:A0PeerAvailability.Assessment,
    )

    data class Preview(
        val status:A0CandidatePreflight.SimulationStatus,
        val preferredIndex:Int?,
        val totalCandidates:Int,
        val distinctClaims:Int,
        val repeatedClaims:Int,
    )

    private data class Key(
        val filename:String,
        val format:String,
        val sizeBytes:Long,
        val bitrateKbps:Int?,
        val durationSeconds:Int?,
        val sampleRateHz:Int?,
        val bitDepth:Int?,
    )

    private fun key(claim:Claim)=Key(
        filename=Normalizer.normalize(claim.filename,Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT),
        format=claim.format.lowercase(Locale.ROOT),
        sizeBytes=claim.sizeBytes,
        bitrateKbps=claim.bitrateKbps,
        durationSeconds=claim.durationSeconds,
        sampleRateHz=claim.sampleRateHz,
        bitDepth=claim.bitDepth,
    )

    /** For the bounded collector: equality of claims, NOT audio identity. */
    fun sameClaim(a:Claim,b:Claim):Boolean=key(a)==key(b)

    /**
     * Returns a read-only observation, NOT a transfer decision.
     * Availability only chooses among otherwise identical declarations.
     * A distinct strong claim at score distance <12 causes abstention.
     */
    fun preview(entries:List<Entry>):Preview {
        // Same filename/bytes is not the same *evidence* when folder context
        // changes identity confidence. Preserve separate risk populations.
        val groups=entries.withIndex().groupBy {
            key(it.value.claim) to it.value.assessment
        }
        val representatives=groups.values.map { group ->
            group.sortedWith(compareByDescending<IndexedValue<Entry>> {
                it.value.score
            }.thenByDescending {
                A0PeerAvailability.priority(it.value.availability)
            }.thenBy { it.index }).first()
        }
        val strong=representatives.filter {
            it.value.assessment.confidence==A0CandidatePreflight.Confidence.STRONG
        }.sortedWith(compareByDescending<IndexedValue<Entry>> { it.value.score }
            .thenBy { it.index })
        val status=when {
            strong.isEmpty() -> A0CandidatePreflight.SimulationStatus.NO_STRONG
            strong.size>1 && strong[0].value.score-strong[1].value.score<12 ->
                A0CandidatePreflight.SimulationStatus.AMBIGUOUS
            else -> A0CandidatePreflight.SimulationStatus.SUGGESTED
        }
        return Preview(
            status=status,
            preferredIndex=if(status==A0CandidatePreflight.SimulationStatus.SUGGESTED)
                strong[0].index else null,
            totalCandidates=entries.size,
            distinctClaims=groups.size,
            repeatedClaims=entries.size-groups.size,
        )
    }
}
