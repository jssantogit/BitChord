package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingReader
import java.util.LinkedList

/** Typed, presentation-neutral reason for v0 artist diversity adjustment. */
internal enum class DiversityReason {
    RECENT_ARTIST_REPEAT,
}

/** v0 tuning policy. Diversity is ephemeral ranking policy, never persisted state/schema. */
internal data class ArtistDiversityPolicy(
    val minimumScore: Double = -100.0,
    val maximumScore: Double = 100.0,
    val lookahead: Int = 8,
    val artistLookback: Int = 3,
    val immediateSameArtistPenalty: Double = -15.0,
) {
    init {
        require(minimumScore.isFinite() && maximumScore.isFinite() && minimumScore < maximumScore) {
            "Diversity score bounds must be finite and increasing"
        }
        require(lookahead > 0) { "Diversity lookahead must be positive" }
        require(artistLookback > 0) { "Artist lookback must be positive" }
        require(immediateSameArtistPenalty.isFinite() && immediateSameArtistPenalty <= 0.0) {
            "Artist repeat penalty must be finite and non-positive"
        }
    }

    fun repeatPenalty(distance: Int): Double {
        require(distance in 1..artistLookback) { "Artist repeat distance is outside policy lookback" }
        val remainingSteps = artistLookback - distance + 1
        return immediateSameArtistPenalty * remainingSteps.toDouble() / artistLookback.toDouble()
    }
}

/** Keeps all upstream ranking information independently inspectable. */
internal data class DiversityRankedCandidate(
    val base: SessionContextRankedCandidate,
    val diversityAdjustment: Double,
    val finalScore: Double,
    val diversityReasons: Set<DiversityReason>,
    val overlappingArtistIds: Set<String>,
)

/**
 * Deterministic bounded-lookahead artist diversity stage.
 *
 * It reads canonical artist grouping once, then performs all selection in memory. Only the
 * next [ArtistDiversityPolicy.lookahead] remaining upstream candidates are eligible for each
 * output position, preventing far-ranked tracks from jumping arbitrarily for variety.
 */
internal class ArtistAwareDiversityReranker(
    private val groupingReader: CanonicalRecordingArtistGroupingReader,
    private val policy: ArtistDiversityPolicy = ArtistDiversityPolicy(),
) {
    suspend fun rerank(
        upstreamRanking: List<SessionContextRankedCandidate>,
    ): List<DiversityRankedCandidate> {
        if (upstreamRanking.isEmpty()) return emptyList()

        val recordingIds = upstreamRanking.map { candidate -> candidate.base.recordingId }
        require(recordingIds.all { it.isNotBlank() }) { "Canonical recordingId must not be blank" }
        require(recordingIds.size == recordingIds.distinct().size) {
            "Diversity reranker requires unique canonical recording candidates"
        }

        val rawGrouping = groupingReader.artistIdsByRecording(recordingIds)
        val artistIdsByRecording = recordingIds.associateWith { recordingId ->
            rawGrouping[recordingId].orEmpty()
                .onEach { artistId ->
                    require(artistId.isNotBlank()) { "Canonical artistId must not be blank" }
                }
                .distinct()
        }

        upstreamRanking.forEach { candidate ->
            require(candidate.finalScore.isFinite()) { "Session Context score must be finite" }
        }

        if (!hasPotentialOverlap(artistIdsByRecording.values)) {
            return upstreamRanking.map { candidate -> neutral(candidate) }
        }

        val remaining = LinkedList<RemainingCandidate>()
        upstreamRanking.forEachIndexed { index, candidate ->
            remaining.add(RemainingCandidate(index, candidate))
        }
        val selected = ArrayList<SelectedCandidate>(upstreamRanking.size)
        val output = ArrayList<DiversityRankedCandidate>(upstreamRanking.size)

        while (remaining.isNotEmpty()) {
            val windowSize = minOf(policy.lookahead, remaining.size)
            val window = ArrayList<EvaluatedCandidate>(windowSize)
            val iterator = remaining.listIterator()
            repeat(windowSize) { windowIndex ->
                val remainingCandidate = iterator.next()
                window += evaluate(
                    remaining = remainingCandidate,
                    windowIndex = windowIndex,
                    selected = selected,
                    artistIdsByRecording = artistIdsByRecording,
                )
            }

            val chosen = window.minWithOrNull(SELECTION_COMPARATOR)
                ?: error("Diversity selection window unexpectedly empty")

            val removalIterator = remaining.listIterator()
            repeat(chosen.windowIndex + 1) { removalIterator.next() }
            removalIterator.remove()

            selected += SelectedCandidate(
                candidate = chosen.remaining.candidate,
                artistIds = artistIdsByRecording.getValue(chosen.remaining.candidate.base.recordingId),
            )
            output += chosen.result
        }

        check(output.size == upstreamRanking.size) { "Diversity reranker changed candidate cardinality" }
        return output
    }

    private fun evaluate(
        remaining: RemainingCandidate,
        windowIndex: Int,
        selected: List<SelectedCandidate>,
        artistIdsByRecording: Map<String, List<String>>,
    ): EvaluatedCandidate {
        val candidate = remaining.candidate
        val candidateArtistIds = artistIdsByRecording.getValue(candidate.base.recordingId)
        val overlap = nearestOverlap(candidateArtistIds, selected)
        val adjustment = overlap?.let { match -> policy.repeatPenalty(match.distance) } ?: 0.0
        check(adjustment.isFinite()) { "Diversity adjustment must be finite" }

        val effectiveOverlap = if (adjustment == 0.0) emptySet() else overlap?.artistIds.orEmpty()
        val rawFinal = candidate.finalScore + adjustment
        val finalScore = finiteBounded(rawFinal)
        val reasons = if (adjustment == 0.0) {
            emptySet()
        } else {
            setOf(DiversityReason.RECENT_ARTIST_REPEAT)
        }

        return EvaluatedCandidate(
            remaining = remaining,
            windowIndex = windowIndex,
            result = DiversityRankedCandidate(
                base = candidate,
                diversityAdjustment = adjustment,
                finalScore = finalScore,
                diversityReasons = reasons,
                overlappingArtistIds = effectiveOverlap,
            ),
        )
    }

    private fun nearestOverlap(
        candidateArtistIds: List<String>,
        selected: List<SelectedCandidate>,
    ): ArtistOverlap? {
        if (candidateArtistIds.isEmpty() || selected.isEmpty()) return null
        val candidateSet = candidateArtistIds.toHashSet()
        val maxDistance = minOf(policy.artistLookback, selected.size)
        for (distance in 1..maxDistance) {
            val previous = selected[selected.size - distance]
            val shared = previous.artistIds.asSequence()
                .filter { it in candidateSet }
                .toCollection(linkedSetOf())
            if (shared.isNotEmpty()) {
                return ArtistOverlap(distance = distance, artistIds = shared)
            }
        }
        return null
    }

    private fun hasPotentialOverlap(groups: Collection<List<String>>): Boolean {
        val seen = HashSet<String>()
        for (group in groups) {
            for (artistId in group) {
                if (!seen.add(artistId)) return true
            }
        }
        return false
    }

    private fun neutral(candidate: SessionContextRankedCandidate): DiversityRankedCandidate =
        DiversityRankedCandidate(
            base = candidate,
            diversityAdjustment = 0.0,
            finalScore = finiteBounded(candidate.finalScore),
            diversityReasons = emptySet(),
            overlappingArtistIds = emptySet(),
        )

    private fun finiteBounded(value: Double): Double = when {
        value.isFinite() -> value.coerceIn(policy.minimumScore, policy.maximumScore)
        value < 0.0 -> policy.minimumScore
        else -> policy.maximumScore
    }

    private data class RemainingCandidate(
        val upstreamIndex: Int,
        val candidate: SessionContextRankedCandidate,
    )

    private data class SelectedCandidate(
        val candidate: SessionContextRankedCandidate,
        val artistIds: List<String>,
    )

    private data class ArtistOverlap(
        val distance: Int,
        val artistIds: Set<String>,
    )

    private data class EvaluatedCandidate(
        val remaining: RemainingCandidate,
        val windowIndex: Int,
        val result: DiversityRankedCandidate,
    )

    private companion object {
        val SELECTION_COMPARATOR: Comparator<EvaluatedCandidate> = Comparator { left, right ->
            compareValuesBy(
                left,
                right,
                { -it.result.finalScore },
                { -it.remaining.candidate.finalScore },
                { it.remaining.upstreamIndex },
                { it.remaining.candidate.base.recordingId },
            )
        }
    }
}
