package com.music.bitchord.acquisition.a0

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Pre-download *claims* classifier, never an audio verifier.
 * Match title in file, artist in file/folder, and inspect peer-advertised
 * duration, encoding, and size. The result is a hint for manual selection.
 * Do not log queries, names, paths, usernames, or ranking details.
 */
internal object A0CandidateRank {
    enum class Match { ALL_TERMS, SOME_TERMS, NO_TERMS }
    enum class Quality {
        LOSSLESS_ADVERTISED, HIGH_ADVERTISED, MEDIUM_ADVERTISED,
        LOW_ADVERTISED, UNKNOWN_ADVERTISED
    }
    enum class Warning { VERSION_MISMATCH, DURATION_MISMATCH, SIZE_SUSPICIOUS }

    data class Ranking(
        val score:Int,
        val match:Match,
        val quality:Quality=Quality.UNKNOWN_ADVERTISED,
        val warnings:List<Warning> = emptyList()
    )
    private fun tokens(value:String):List<String> {
        val folded=Normalizer.normalize(value,Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT)
        return folded.split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
    }

    fun rank(
        searchPhrase:String?, name:String, declaredBitrateKbps:Int?,
        remotePath:String?=null, format:String?=null,
        declaredSampleRateHz:Int?=null, declaredBitDepth:Int?=null,
        declaredDurationSeconds:Int?=null, expectedDurationSeconds:Int?=null,
        sizeBytes:Long?=null, queueLength:Int?=null
    ):Ranking {
        val parts=searchPhrase.orEmpty().split(Regex("\\s+[-–—]\\s+"),limit=2)
        val titleWords=tokens(if(parts.size==2)parts[1] else searchPhrase.orEmpty()).distinct()
        val artistWords=if(parts.size==2) tokens(parts[0]).distinct() else emptyList()
        val fileWords=tokens(name.substringBeforeLast('.',name)).toSet()
        val pathWords=tokens(remotePath.orEmpty()).toSet()
        val allWords=fileWords+pathWords
        val titleMatches=titleWords.count { it in fileWords }
        val artistMatches=artistWords.count { it in allWords }
        val titleComplete=titleWords.isNotEmpty() && titleMatches==titleWords.size
        val artistComplete=artistWords.isEmpty() || artistMatches==artistWords.size
        val match=when {
            titleComplete && artistComplete -> Match.ALL_TERMS
            titleMatches>0 || artistMatches>0 -> Match.SOME_TERMS
            else -> Match.NO_TERMS
        }
        val titleScore=when {
            titleComplete -> 52
            titleWords.isEmpty() -> 0
            else -> 32*titleMatches/titleWords.size
        }
        val artistScore=when {
            artistWords.isEmpty() -> 0
            artistComplete -> 22
            else -> 10*artistMatches/artistWords.size
        }
        val ext=(format ?: name.substringAfterLast('.', "")).lowercase(Locale.ROOT)
        val validRate=declaredBitrateKbps?.takeIf { it in 32..1536 }
        val lossless=ext in setOf("flac","wav")
        val quality=when {
            lossless && declaredSampleRateHz in 8000..384000 &&
                declaredBitDepth in 8..32 -> Quality.LOSSLESS_ADVERTISED
            lossless -> Quality.UNKNOWN_ADVERTISED
            validRate!=null && validRate>=256 -> Quality.HIGH_ADVERTISED
            validRate!=null && validRate>=160 -> Quality.MEDIUM_ADVERTISED
            validRate!=null -> Quality.LOW_ADVERTISED
            else -> Quality.UNKNOWN_ADVERTISED
        }
        val qualityScore=when(quality) {
            Quality.LOSSLESS_ADVERTISED -> 18
            Quality.HIGH_ADVERTISED -> 13
            Quality.MEDIUM_ADVERTISED -> 6
            Quality.LOW_ADVERTISED -> -12
            Quality.UNKNOWN_ADVERTISED -> if (lossless) 6 else 0
        }
        var score=titleScore+artistScore+qualityScore
        val warnings=mutableListOf<Warning>()
        val nameWords=fileWords
        if (setOf("live","remix","karaoke","instrumental","nightcore",
                "slowed","cover","sped","reverb") .any { it in nameWords }) {
            score-=30
            warnings.add(Warning.VERSION_MISMATCH)
        }
        if (declaredDurationSeconds!=null && expectedDurationSeconds!=null &&
            declaredDurationSeconds>0 && expectedDurationSeconds>0) {
            val delta=abs(declaredDurationSeconds.toLong()-expectedDurationSeconds.toLong())
            if (delta>25L) {
                score-=30
                warnings.add(Warning.DURATION_MISMATCH)
            } else if(delta>8L) {
                score-=10
                warnings.add(Warning.DURATION_MISMATCH)
            } else score+=8
        }
        if (validRate!=null && declaredDurationSeconds!=null &&
            declaredDurationSeconds in 30..14400 && sizeBytes!=null) {
            val predicted=validRate.toLong()*125L*declaredDurationSeconds
            if (predicted>0 && (sizeBytes<predicted*4L/10L || sizeBytes>predicted*22L/10L)) {
                score-=20
                warnings.add(Warning.SIZE_SUSPICIOUS)
            }
        }
        if(queueLength!=null)score-=when {
            queueLength>25 -> 8
            queueLength>0 -> 3
            else -> 0
        }
        return Ranking(score.coerceIn(0,100),match,quality,warnings)
    }
}
