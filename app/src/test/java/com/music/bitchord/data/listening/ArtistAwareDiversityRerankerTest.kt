package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistAwareDiversityRerankerTest {

    @Test
    fun `empty input is empty and avoids catalog read`() = runTest {
        val reader = FakeGroupingReader(emptyMap())

        val result = ArtistAwareDiversityReranker(reader).rerank(emptyList())

        assertTrue(result.isEmpty())
        assertEquals(0, reader.reads)
    }

    @Test
    fun `single candidate and missing artist metadata stay neutral`() = runTest {
        val candidate = contextual("recording:a", 42.0)
        val reader = FakeGroupingReader(mapOf("recording:a" to emptyList()))

        val result = ArtistAwareDiversityReranker(reader).rerank(listOf(candidate)).single()

        assertEquals(candidate, result.base)
        assertEquals(0.0, result.diversityAdjustment, EPSILON)
        assertEquals(42.0, result.finalScore, EPSILON)
        assertTrue(result.diversityReasons.isEmpty())
        assertTrue(result.overlappingArtistIds.isEmpty())
    }

    @Test
    fun `no canonical artist metadata preserves upstream order exactly`() = runTest {
        val upstream = listOf(
            contextual("recording:z", 10.0),
            contextual("recording:a", 90.0),
            contextual("recording:m", 40.0),
        )
        val reader = FakeGroupingReader(upstream.associate { it.base.recordingId to emptyList() })

        val result = ArtistAwareDiversityReranker(reader).rerank(upstream)

        assertEquals(upstream, result.map { it.base })
        assertTrue(result.all { it.diversityAdjustment == 0.0 })
    }

    @Test
    fun `all distinct canonical artists preserve upstream order`() = runTest {
        val upstream = listOf(
            contextual("recording:a", 90.0),
            contextual("recording:b", 80.0),
            contextual("recording:c", 70.0),
        )
        val reader = FakeGroupingReader(
            mapOf(
                "recording:a" to listOf("artist:a"),
                "recording:b" to listOf("artist:b"),
                "recording:c" to listOf("artist:c"),
            ),
        )

        val result = ArtistAwareDiversityReranker(reader).rerank(upstream)

        assertEquals(upstream, result.map { it.base })
        assertTrue(result.all { it.diversityAdjustment == 0.0 })
    }

    @Test
    fun `repeat penalty decays from immediate to distance three and expires outside lookback`() = runTest {
        suspend fun adjustment(ids: List<String>, target: String): Double {
            val upstream = ids.mapIndexed { index, id -> contextual(id, 100.0 - index) }
            val reader = FakeGroupingReader(
                ids.associateWith { id ->
                    if (id == "a1" || id == target) listOf("artist:a") else listOf("artist:$id")
                },
            )
            return ArtistAwareDiversityReranker(
                reader,
                ArtistDiversityPolicy(lookahead = 1, artistLookback = 3),
            ).rerank(upstream).first { it.base.base.recordingId == target }.diversityAdjustment
        }

        assertEquals(-15.0, adjustment(listOf("a1", "a2"), "a2"), EPSILON)
        assertEquals(-10.0, adjustment(listOf("a1", "b", "a2"), "a2"), EPSILON)
        assertEquals(-5.0, adjustment(listOf("a1", "b", "c", "a2"), "a2"), EPSILON)
        assertEquals(0.0, adjustment(listOf("a1", "b", "c", "d", "a2"), "a2"), EPSILON)
    }

    @Test
    fun `nearest shared artist wins and older overlaps do not accumulate`() = runTest {
        val upstream = listOf(
            contextual("a-old", 100.0),
            contextual("b", 99.0),
            contextual("a-near", 98.0),
            contextual("candidate", 97.0),
        )
        val reader = FakeGroupingReader(
            mapOf(
                "a-old" to listOf("artist:a"),
                "b" to listOf("artist:b"),
                "a-near" to listOf("artist:a"),
                "candidate" to listOf("artist:a"),
            ),
        )

        val result = ArtistAwareDiversityReranker(
            reader,
            ArtistDiversityPolicy(lookahead = 1, artistLookback = 3),
        ).rerank(upstream).last()

        assertEquals(-15.0, result.diversityAdjustment, EPSILON)
        assertEquals(setOf("artist:a"), result.overlappingArtistIds)
        assertEquals(setOf(DiversityReason.RECENT_ARTIST_REPEAT), result.diversityReasons)
    }

    @Test
    fun `multiple shared artist ids cause one strongest penalty and collaboration overlap counts`() = runTest {
        val upstream = listOf(
            contextual("collab-1", 90.0),
            contextual("collab-2", 88.0),
            contextual("x-only", 70.0),
        )
        val reader = FakeGroupingReader(
            mapOf(
                "collab-1" to listOf("artist:x", "artist:y"),
                "collab-2" to listOf("artist:x", "artist:y"),
                "x-only" to listOf("artist:x"),
            ),
        )
        val result = ArtistAwareDiversityReranker(
            reader,
            ArtistDiversityPolicy(lookahead = 1),
        ).rerank(upstream)

        assertEquals(-15.0, result[1].diversityAdjustment, EPSILON)
        assertEquals(setOf("artist:x", "artist:y"), result[1].overlappingArtistIds)
        assertEquals(-15.0, result[2].diversityAdjustment, EPSILON)
        assertEquals(setOf("artist:x"), result[2].overlappingArtistIds)
        assertFalse(result[1].diversityAdjustment == -30.0)
    }

    @Test
    fun `typed reason appears only when repeat adjustment is nonzero`() = runTest {
        val upstream = listOf(contextual("a1", 90.0), contextual("a2", 80.0))
        val reader = FakeGroupingReader(
            mapOf("a1" to listOf("artist:a"), "a2" to listOf("artist:a")),
        )
        val neutralPolicy = ArtistDiversityPolicy(immediateSameArtistPenalty = 0.0)

        val penalized = ArtistAwareDiversityReranker(reader).rerank(upstream)[1]
        val neutral = ArtistAwareDiversityReranker(reader, neutralPolicy).rerank(upstream)[1]

        assertEquals(setOf(DiversityReason.RECENT_ARTIST_REPEAT), penalized.diversityReasons)
        assertTrue(neutral.diversityReasons.isEmpty())
        assertTrue(neutral.overlappingArtistIds.isEmpty())
    }

    @Test
    fun `diversity can locally promote another artist without overriding a large relevance gap`() = runTest {
        val artists = mapOf(
            "a1" to listOf("artist:a"),
            "a2" to listOf("artist:a"),
            "b1" to listOf("artist:b"),
        )

        val reordered = ArtistAwareDiversityReranker(FakeGroupingReader(artists)).rerank(
            listOf(contextual("a1", 90.0), contextual("a2", 88.0), contextual("b1", 80.0)),
        )
        val gapWins = ArtistAwareDiversityReranker(FakeGroupingReader(artists)).rerank(
            listOf(contextual("a1", 90.0), contextual("a2", 88.0), contextual("b1", 60.0)),
        )

        assertEquals(listOf("a1", "b1", "a2"), reordered.map { it.base.base.recordingId })
        assertEquals(listOf("a1", "a2", "b1"), gapWins.map { it.base.base.recordingId })
        assertEquals(73.0, gapWins[1].finalScore, EPSILON)
    }

    @Test
    fun `bounded lookahead prevents far jump until candidate enters a later window`() = runTest {
        val upstream = listOf(
            contextual("a1", 100.0),
            contextual("a2", 99.0),
            contextual("a3", 98.0),
            contextual("b1", 97.0),
        )
        val reader = FakeGroupingReader(
            mapOf(
                "a1" to listOf("artist:a"),
                "a2" to listOf("artist:a"),
                "a3" to listOf("artist:a"),
                "b1" to listOf("artist:b"),
            ),
        )

        val result = ArtistAwareDiversityReranker(
            reader,
            ArtistDiversityPolicy(lookahead = 2),
        ).rerank(upstream)

        assertEquals(listOf("a1", "a2", "b1", "a3"), result.map { it.base.base.recordingId })
    }

    @Test
    fun `every upstream candidate appears exactly once and cardinality is preserved`() = runTest {
        val upstream = (0 until 25).map { index -> contextual("recording:$index", 100.0 - index) }
        val reader = FakeGroupingReader(
            upstream.associate { candidate ->
                candidate.base.recordingId to listOf("artist:${candidate.base.recordingId.substringAfter(':').toInt() % 4}")
            },
        )

        val result = ArtistAwareDiversityReranker(reader).rerank(upstream)
        val outputIds = result.map { it.base.base.recordingId }

        assertEquals(upstream.size, result.size)
        assertEquals(upstream.map { it.base.recordingId }.toSet(), outputIds.toSet())
        assertEquals(outputIds.size, outputIds.distinct().size)
    }

    @Test
    fun `selection tie break uses diversity score then upstream score then original upstream index`() = runTest {
        val reader = FakeGroupingReader(
            mapOf(
                "a1" to listOf("artist:a"),
                "a2" to listOf("artist:a"),
                "b1" to listOf("artist:b"),
                "c1" to listOf("artist:c"),
            ),
        )
        val upstreamScoreBreak = ArtistAwareDiversityReranker(reader).rerank(
            listOf(
                contextual("a1", 100.0),
                contextual("a2", 90.0),
                contextual("b1", 75.0),
            ),
        )
        val originalIndexBreak = ArtistAwareDiversityReranker(reader).rerank(
            listOf(
                contextual("b1", 50.0),
                contextual("c1", 50.0),
            ),
        )

        assertEquals(listOf("a1", "a2", "b1"), upstreamScoreBreak.map { it.base.base.recordingId })
        assertEquals(75.0, upstreamScoreBreak[1].finalScore, EPSILON)
        assertEquals(90.0, upstreamScoreBreak[1].base.finalScore, EPSILON)
        assertEquals(listOf("b1", "c1"), originalIndexBreak.map { it.base.base.recordingId })
    }

    @Test
    fun `repeated reruns are deterministic and perform one logical catalog read without candidate N plus one`() = runTest {
        val upstream = (0 until 100).map { index -> contextual("recording:$index", 100.0 - index) }
        val reader = FakeGroupingReader(
            upstream.associate { candidate ->
                candidate.base.recordingId to listOf("artist:${candidate.base.recordingId.substringAfter(':').toInt() % 7}")
            },
        )
        val reranker = ArtistAwareDiversityReranker(reader)

        val first = reranker.rerank(upstream)
        val second = reranker.rerank(upstream)

        assertEquals(first, second)
        assertEquals(2, reader.reads)
        assertEquals(listOf(100, 100), reader.requestSizes)
        assertTrue(ArtistAwareDiversityReranker::class.java.declaredFields.none {
            it.type.name.contains("Random", ignoreCase = true)
        })
    }

    @Test
    fun `final scores stay finite and bounded while previous stage remains untouched`() = runTest {
        val first = contextual("a1", -95.0)
        val second = contextual("a2", -95.0)
        val reader = FakeGroupingReader(
            mapOf("a1" to listOf("artist:a"), "a2" to listOf("artist:a")),
        )

        val result = ArtistAwareDiversityReranker(reader).rerank(listOf(first, second))

        assertEquals(first, result[0].base)
        assertEquals(second, result[1].base)
        assertEquals(-100.0, result[1].finalScore, EPSILON)
        assertTrue(result.all { it.finalScore.isFinite() && it.finalScore in -100.0..100.0 })
        assertEquals(-95.0, result[1].base.finalScore, EPSILON)
    }

    @Test
    fun `invalid policy and malformed fake artist ids are rejected deterministically`() = runTest {
        assertTrue(runCatching { ArtistDiversityPolicy(lookahead = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ArtistDiversityPolicy(artistLookback = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ArtistDiversityPolicy(immediateSameArtistPenalty = 1.0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { ArtistDiversityPolicy(minimumScore = 10.0, maximumScore = 10.0) }
                .exceptionOrNull() is IllegalArgumentException,
        )

        val malformed = FakeGroupingReader(mapOf("recording:a" to listOf("artist:a", "", "artist:a")))
        val failure = runCatching {
            ArtistAwareDiversityReranker(malformed).rerank(listOf(contextual("recording:a", 10.0)))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun contextual(recordingId: String, score: Double): SessionContextRankedCandidate {
        val base = RankedRecordingCandidate(
            recordingId = recordingId,
            score = score,
            components = RecommendationScoreComponents(
                explicitPreference = score,
                completionEvidence = 0.0,
                earlySkipPenalty = 0.0,
                lateSkipPenalty = 0.0,
                manualSelectionEvidence = 0.0,
                replayEvidence = 0.0,
                behavioralEvidence = 0.0,
                recentListeningCooldown = 0.0,
                recentRatingChange = 0.0,
                total = score,
            ),
            reasons = emptySet(),
            features = RecordingRecommendationFeatures(
                recordingId = recordingId,
                explicitRating = null,
                explicitRatedAtEpochMs = null,
                lastRatingChangedAtEpochMs = null,
                lastRatingChangeDirection = null,
                behaviorSummary = RecordingBehaviorSummary(
                    recordingId = recordingId,
                    starts = 0,
                    completions = 0,
                    earlySkips = 0,
                    lateSkips = 0,
                    interruptions = 0,
                    totalListenedMs = 0,
                    lastEventAtEpochMs = null,
                ),
                manualSelections = 0,
                replays = 0,
                lastAudibleListenAtEpochMs = null,
            ),
        )
        return SessionContextRankedCandidate(
            base = base,
            sessionAdjustment = 0.0,
            finalScore = score,
            sessionReasons = emptySet(),
        )
    }

    private class FakeGroupingReader(
        private val values: Map<String, List<String>>,
    ) : CanonicalRecordingArtistGroupingReader {
        var reads = 0
            private set
        val requestSizes = mutableListOf<Int>()

        override suspend fun artistIdsByRecording(
            recordingIds: List<String>,
        ): Map<String, List<String>> {
            reads += 1
            requestSizes += recordingIds.size
            return recordingIds.associateWith { values[it].orEmpty() }
        }
    }

    private companion object {
        const val EPSILON = 0.000001
    }
}
