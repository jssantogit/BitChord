package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader

/**
 * Canonical, read-only numeric evidence; Catalog validates the current account scope.
 * The caller must not interpret starred as an explicit numeric rating.
 */
internal enum class RemoteSmartPlaylistRatingKind { VALUE, CLEARED, NOT_PROVIDED, INVALID }

internal data class RemoteSmartPlaylistRatingEvidence(
    val recordingId: String,
    val kind: RemoteSmartPlaylistRatingKind,
    val value: Int? = null,
)

internal data class RemoteSmartPlaylistRatingSnapshot(
    val complete: Boolean,
    val evidence: List<RemoteSmartPlaylistRatingEvidence>,
)

internal fun interface RemoteSmartPlaylistRatingReader {
    suspend fun read(): RemoteSmartPlaylistRatingSnapshot
}

/** Never copies a remote score into first-party feedback. Disagreement fails closed. */
internal fun effectiveSmartPlaylistRating(
    local: Double?,
    remote: List<RemoteSmartPlaylistRatingEvidence>,
): Double? {
    if (remote.isEmpty()) return local
    if (remote.any { it.kind == RemoteSmartPlaylistRatingKind.INVALID ||
        (it.kind == RemoteSmartPlaylistRatingKind.VALUE && it.value !in 1..5) ||
        (it.kind != RemoteSmartPlaylistRatingKind.VALUE && it.value != null)
    }) return null

    val values = remote.filter { it.kind == RemoteSmartPlaylistRatingKind.VALUE }
        .mapNotNull { it.value }.distinct()
    if (values.size > 1) return null
    val cleared = remote.any { it.kind == RemoteSmartPlaylistRatingKind.CLEARED }
    if (cleared && (values.isNotEmpty() || local != null)) return null
    val remoteValue = values.singleOrNull()?.toDouble()
    if (local != null && remoteValue != null && local != remoteValue) return null
    return local ?: remoteValue
}

/** All identities and reasons are canonical/first-party; no playlist is persisted. */
internal data class RuleBasedRecordingCandidate(
    val recordingId: String,
    val explicitRating: Double?,
    val audibleCompletions: Long,
    val lastAudibleAtEpochMs: Long?,
    val durationMs: Long?,
    val available: Boolean,
)

internal data class RuleBasedSmartPlaylistQuery(
    val minimumExplicitRating: Double? = null,
    val minimumCompletions: Long? = null,
    val minimumDaysSinceAudible: Long? = null,
    val maximumDaysSinceAudible: Long? = null,
    val minimumDurationMs: Long? = null,
    val limit: Int = 50,
) {
    init {
        require(minimumExplicitRating != null || minimumCompletions != null ||
            minimumDaysSinceAudible != null || maximumDaysSinceAudible != null ||
            minimumDurationMs != null) { "At least one explicit rule is required" }
        require(minimumExplicitRating == null ||
            (minimumExplicitRating.isFinite() && minimumExplicitRating in 1.0..5.0))
        require(minimumCompletions == null || minimumCompletions > 0)
        require(minimumDaysSinceAudible == null || minimumDaysSinceAudible >= 0)
        require(maximumDaysSinceAudible == null || maximumDaysSinceAudible >= 0)
        require(minimumDurationMs == null || minimumDurationMs > 0)
        require(limit in 1..100)
    }
}

internal enum class RuleBasedInclusionReason {
    EXPLICIT_RATING, CONFIRMED_COMPLETION, AUDIBLE_ABSENCE, AUDIBLE_RECENCY, TRUSTED_DURATION,
}

internal data class RuleBasedSmartPlaylistItem(
    val recordingId: String,
    val reasons: Set<RuleBasedInclusionReason>,
    val explicitRating: Double?,
    val confirmedCompletions: Long,
)

/**
 * Pure deterministic AND-rule evaluator. Duplicate canonical media assets converge
 * to one Recording; contradictory snapshots for one Recording are rejected instead
 * of selecting whichever provider row happened to arrive first.
 */
internal class RuleBasedSmartPlaylistPlanner(
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    fun preview(
        input: List<RuleBasedRecordingCandidate>,
        query: RuleBasedSmartPlaylistQuery,
    ): List<RuleBasedSmartPlaylistItem> {
        val now = nowEpochMs()
        if (now < 0L) return emptyList()
        return input.groupBy { it.recordingId }
            .toSortedMap()
            .values
            .mapNotNull { grouped ->
                if (grouped.distinct().size != 1) return@mapNotNull null
                val item = grouped.first()
                if (item.recordingId.isBlank() || !item.available ||
                    item.audibleCompletions < 0 ||
                    item.explicitRating?.isFinite() == false
                ) return@mapNotNull null

                val reasons = linkedSetOf<RuleBasedInclusionReason>()
                if (query.minimumExplicitRating != null) {
                    val rating = item.explicitRating ?: return@mapNotNull null
                    if (rating < query.minimumExplicitRating) return@mapNotNull null
                    reasons += RuleBasedInclusionReason.EXPLICIT_RATING
                }
                if (query.minimumCompletions != null) {
                    if (item.audibleCompletions < query.minimumCompletions) return@mapNotNull null
                    reasons += RuleBasedInclusionReason.CONFIRMED_COMPLETION
                }
                if (query.minimumDurationMs != null) {
                    val duration = item.durationMs ?: return@mapNotNull null
                    if (duration < query.minimumDurationMs) return@mapNotNull null
                    reasons += RuleBasedInclusionReason.TRUSTED_DURATION
                }
                if (query.minimumDaysSinceAudible != null || query.maximumDaysSinceAudible != null) {
                    // A bare PLAYBACK_STARTED must never be interpreted as an audible listen.
                    if (item.audibleCompletions < 1L) return@mapNotNull null
                    val last = item.lastAudibleAtEpochMs ?: return@mapNotNull null
                    if (last < 0L || last > now) return@mapNotNull null
                    val ageDays = (now - last) / 86_400_000L
                    if (query.minimumDaysSinceAudible != null) {
                        if (ageDays < query.minimumDaysSinceAudible) return@mapNotNull null
                        reasons += RuleBasedInclusionReason.AUDIBLE_ABSENCE
                    }
                    if (query.maximumDaysSinceAudible != null) {
                        if (ageDays > query.maximumDaysSinceAudible) return@mapNotNull null
                        reasons += RuleBasedInclusionReason.AUDIBLE_RECENCY
                    }
                }
                RuleBasedSmartPlaylistItem(
                    recordingId = item.recordingId,
                    reasons = reasons,
                    explicitRating = item.explicitRating,
                    confirmedCompletions = item.audibleCompletions,
                )
            }.sortedWith(
                compareByDescending<RuleBasedSmartPlaylistItem> { it.explicitRating ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.confirmedCompletions }
                    .thenBy { it.recordingId },
            ).take(query.limit)
    }
}

/**
 * Read-only first-party adapter: only eligible canonical Recording IDs from
 * Catalog and real terminal audible evidence from Listening. No writes/network.
 * Genre/date rules are intentionally absent until canonical metadata supports
 * them; a provider-supplied guess is not a trusted rule.
 */
internal class CanonicalRuleBasedSmartPlaylistPreview(
    private val availability: CanonicalLibraryAvailabilityReader,
    private val catalog: CanonicalCatalogDao,
    private val ratings: ExplicitRecordingRatingReader,
    private val listening: ListeningBehaviorReader,
    private val planner: RuleBasedSmartPlaylistPlanner = RuleBasedSmartPlaylistPlanner(),
    private val remoteRatings: RemoteSmartPlaylistRatingReader = RemoteSmartPlaylistRatingReader {
        RemoteSmartPlaylistRatingSnapshot(complete = false, evidence = emptyList())
    },
) {
    suspend fun preview(query: RuleBasedSmartPlaylistQuery): List<RuleBasedSmartPlaylistItem> {
        val ids = availability.availableRecordingIds().distinct().sorted()
        if (ids.size > 5_000 || ids.any(String::isBlank)) return emptyList()
        // Read one validated account snapshot for this evaluation, never once per track.
        // A failed/changed account is unavailable, not a stale cached permission.
        val snapshot = remoteRatings.read()
        val remoteByRecording = if (snapshot.complete) {
            snapshot.evidence.filter { it.recordingId.isNotBlank() }
                .groupBy { it.recordingId }
        } else emptyMap()
        val rows = ids.mapNotNull { id ->
            val metadata = catalog.recordingById(id) ?: return@mapNotNull null
            val summary = listening.behaviorSummary(id)
            val audibleAt = listening.recentEventsForRecording(id).asSequence()
                .filter { it.type == ListeningEventType.PLAYBACK_COMPLETED &&
                    (it.listenedDurationMs ?: 0L) > 0L }
                .map { it.occurredAtEpochMs }.maxOrNull()
            RuleBasedRecordingCandidate(
                recordingId = metadata.id,
                explicitRating = effectiveSmartPlaylistRating(
                    ratings.currentRating(id)?.value,
                    remoteByRecording[id].orEmpty(),
                ),
                audibleCompletions = summary.completions,
                lastAudibleAtEpochMs = audibleAt,
                durationMs = metadata.durationMs,
                available = true,
            )
        }
        return planner.preview(rows, query)
    }
}
