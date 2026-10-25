package com.music.bitchord.acquisition.a0

import kotlin.math.abs

/**
 * Conservative pre-download assessment of *untrusted peer metadata*.
 *
 * Strong means "sufficiently plausible to consider in a future opt-in mode",
 * NOT verified audio, a trusted source, or permission to initiate a transfer.
 * This policy has no network, filesystem, or playback side effects.
 */
internal object A0CandidatePreflight {
    enum class Confidence { STRONG, REVIEW, UNSAFE }

    enum class Reason {
        TITLE_MISMATCH,
        ARTIST_UNCONFIRMED,
        VERSION_CONFLICT,
        DURATION_UNCONFIRMED,
        DURATION_CONFLICT,
        SIZE_CONFLICT,
        QUALITY_UNCONFIRMED,
    }

    data class Assessment(
        val confidence:Confidence,
        val reasons:List<Reason>,
    )

    /** Separate confidence from ranking. The user may still manually inspect all choices. */
    fun assess(
        searchPhrase:String?,
        ranking:A0CandidateRank.Ranking,
        declaredBitrateKbps:Int?,
        declaredDurationSeconds:Int?,
        expectedDurationSeconds:Int?,
        format:String,
        sizeBytes:Long,
    ):Assessment {
        val reasons=linkedSetOf<Reason>()
        val hard=linkedSetOf<Reason>()
        val queryParts=searchPhrase.orEmpty().split(Regex("\\s+[-–—]\\s+"),limit=2)
        val hasArtistAndTitle=queryParts.size==2 &&
            queryParts.all { it.isNotBlank() }

        // A title-only request may find the correct song, but it cannot prove
        // the artist. Never let a high bitrate compensate for this ambiguity.
        when(ranking.match) {
            A0CandidateRank.Match.NO_TERMS,
            A0CandidateRank.Match.SOME_TERMS -> {
                reasons.add(Reason.TITLE_MISMATCH)
                hard.add(Reason.TITLE_MISMATCH)
            }
            A0CandidateRank.Match.ALL_TERMS -> Unit
        }
        if(!hasArtistAndTitle) reasons.add(Reason.ARTIST_UNCONFIRMED)

        if(A0CandidateRank.Warning.VERSION_MISMATCH in ranking.warnings) {
            reasons.add(Reason.VERSION_CONFLICT)
            hard.add(Reason.VERSION_CONFLICT)
        }
        if(A0CandidateRank.Warning.SIZE_SUSPICIOUS in ranking.warnings) {
            reasons.add(Reason.SIZE_CONFLICT)
            hard.add(Reason.SIZE_CONFLICT)
        }

        val length=declaredDurationSeconds?.takeIf { it in 15..7200 }
        val targetLength=expectedDurationSeconds?.takeIf { it in 15..7200 }
        if(length==null || targetLength==null) {
            reasons.add(Reason.DURATION_UNCONFIRMED)
        } else if(abs(length.toLong()-targetLength.toLong())>8L) {
            reasons.add(Reason.DURATION_CONFLICT)
            hard.add(Reason.DURATION_CONFLICT)
        }

        // Approximate CBR-size check. A peer's bitrate is unverified and may
        // be an average for VBR. Keep broad tolerance to avoid false precision.
        val ext=format.lowercase(java.util.Locale.ROOT)
        val goodBitrate=declaredBitrateKbps?.takeIf { it in 32..1536 }
        when(ext) {
            "mp3" -> {
                if(goodBitrate==null || goodBitrate<256) {
                    reasons.add(Reason.QUALITY_UNCONFIRMED)
                }
                if(goodBitrate!=null && length!=null) {
                    val predicted=goodBitrate.toLong()*125L*length
                    if(predicted>0 && (sizeBytes<predicted*55L/100L ||
                        sizeBytes>predicted*165L/100L)) {
                        reasons.add(Reason.SIZE_CONFLICT)
                        hard.add(Reason.SIZE_CONFLICT)
                    }
                }
            }
            "flac", "wav" -> {
                if(ranking.quality!=A0CandidateRank.Quality.LOSSLESS_ADVERTISED) {
                    reasons.add(Reason.QUALITY_UNCONFIRMED)
                }
                // Very small files claimed as full-length FLAC/WAV demand
                // review. This is not a proof of compression quality.
                if(length!=null && sizeBytes<length.toLong()*5_000L) {
                    reasons.add(Reason.SIZE_CONFLICT)
                    hard.add(Reason.SIZE_CONFLICT)
                }
            }
            else -> reasons.add(Reason.QUALITY_UNCONFIRMED)
        }
        val confidence=when {
            hard.isNotEmpty() -> Confidence.UNSAFE
            reasons.isNotEmpty() || ranking.score<65 -> Confidence.REVIEW
            else -> Confidence.STRONG
        }
        return Assessment(confidence,reasons.toList())
    }

    data class Weighted(val assessment:Assessment,val score:Int)

    /**
     * A hint for future opt-in automation, never a transfer instruction.
     * Abstain unless the best is strong and clearly exceeds any rival strong
     * candidate; tie/near-tie means retain manual selection.
     */
    fun suggestIndex(candidates:List<Weighted>):Int? {
        val strong=candidates.withIndex()
            .filter { it.value.assessment.confidence==Confidence.STRONG }
            .sortedWith(compareByDescending<IndexedValue<Weighted>> { it.value.score }
                .thenBy { it.index })
        if(strong.isEmpty()) return null
        if(strong.size>1 && strong[0].value.score-strong[1].value.score<12) return null
        return strong[0].index
    }
}
