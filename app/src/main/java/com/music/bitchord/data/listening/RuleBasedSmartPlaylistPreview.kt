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
    val ratingEvidenceUnknown: Boolean = false,
    val title: String? = null,
    val artistDisplay: String? = null,
    val earlySkips: Long = 0,
    val lateSkips: Long = 0,
    val releaseTitles: List<String> = emptyList(),
    /** Null = unavailable provenance; never treat it as a negative match. */
    val sourceKinds: Set<SmartPlaylistSourceKind>? = null,
    /** A positive kind remains provable even if other provider copies are unknown. */
    val sourceKindsComplete: Boolean = true,
    val releaseYears: List<Int> = emptyList(),
    val releaseYearEvidenceUnknown: Boolean = true,
    val albumArtistNames: List<String> = emptyList(),
    val albumArtistEvidenceUnknown: Boolean = true,
    val trackNumbers: List<Int> = emptyList(),
    val trackNumberEvidenceUnknown: Boolean = true,
    val trackArtistNames: List<String> = emptyList(),
    val trackArtistEvidenceUnknown: Boolean = true,
    val localRatingUpdatedAtEpochMs: Long? = null,
    /** An absent first-party rating is only known after the local store was queried. */
    val localRatingDateEvidenceKnown: Boolean = false,
    /** A canonical release has an artwork URI; not evidence of a loaded correct image. */
    val hasReleaseArtworkReference: Boolean = false,
    val localAddedEpochMs: List<Long> = emptyList(),
    val localAddedDateEvidenceUnknown: Boolean = true,
    /** Real canonical ReleaseTrack IDs, never matched by album title. */
    val canonicalReleaseIds: List<String> = emptyList(),
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
        // Legacy V1 definitions still preview large libraries during migration.
        // Avoid allocating one list per ID; invalidate a Recording forever once
        // two source snapshots disagree, even if the first repeats afterward.
        val uniqueById = HashMap<String, RuleBasedRecordingCandidate>()
        val conflictedIds = HashSet<String>()
        for (item in input) {
            val id = item.recordingId
            if (id in conflictedIds) continue
            val previous = uniqueById[id]
            when {
                previous == null -> uniqueById[id] = item
                previous != item -> {
                    uniqueById.remove(id)
                    conflictedIds.add(id)
                }
            }
        }
        return uniqueById.values.asSequence()
            .mapNotNull { item ->
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
            ).take(query.limit).toList()
    }
}

/**
 * Read-only first-party adapter: only eligible canonical Recording IDs from
 * Catalog and real terminal audible evidence from Listening. No writes/network.
 * Genre/date rules are intentionally absent until canonical metadata supports
 * them; a provider-supplied guess is not a trusted rule.
 */
/** Remote numeric evidence is read only when the query can observe it. */
/** Scan every nested rule exactly once; unrelated metadata must not be fetched. */
internal fun SmartPlaylistRule.referencedFields(): Set<SmartPlaylistRuleField> = when (this) {
    is SmartPlaylistRule.Comparison -> setOf(field)
    is SmartPlaylistRule.Reference -> emptySet()
    is SmartPlaylistRule.Group -> children.flatMap { it.referencedFields() }.toSet()
    is SmartPlaylistRule.Not -> child.referencedFields()
}

/**
 * Sort dependencies must be fetched as well as AST fields: RATING_DESC uses
 * completions for tie-breaking, while text-sort-only queries need neither the
 * rating table nor the listening event aggregate.
 */
internal fun SmartPlaylistV2Query.requiredEvidenceFields(): Set<SmartPlaylistRuleField> {
    val sortFields = when (sort) {
        SmartPlaylistV2Sort.RATING_DESC -> setOf(
            SmartPlaylistRuleField.EXPLICIT_RATING,
            SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
        )
        SmartPlaylistV2Sort.RATING_ASC -> setOf(SmartPlaylistRuleField.EXPLICIT_RATING)
        SmartPlaylistV2Sort.COMPLETIONS_ASC,
        SmartPlaylistV2Sort.COMPLETIONS_DESC ->
            setOf(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS)
        SmartPlaylistV2Sort.LAST_AUDIBLE_ASC,
        SmartPlaylistV2Sort.LAST_AUDIBLE_DESC ->
            setOf(SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE)
        SmartPlaylistV2Sort.LOCAL_RATING_NEWEST,
        SmartPlaylistV2Sort.LOCAL_RATING_OLDEST ->
            setOf(SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING)
        SmartPlaylistV2Sort.STABLE_RANDOM_ALBUMS -> setOf(
            SmartPlaylistRuleField.RELEASE_TITLE,
            SmartPlaylistRuleField.TRACK_NUMBER,
        )
        else -> emptySet()
    }
    return rules.referencedFields() + sortFields
}

private fun SmartPlaylistRule.needsSourceKinds(): Boolean = when (this) {
    is SmartPlaylistRule.Comparison -> field == SmartPlaylistRuleField.SOURCE_KIND ||
        field == SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED
    is SmartPlaylistRule.Reference -> false
    is SmartPlaylistRule.Group -> children.any { it.needsSourceKinds() }
    is SmartPlaylistRule.Not -> child.needsSourceKinds()
}

private fun SmartPlaylistRule.needsExplicitRating(): Boolean = when (this) {
    is SmartPlaylistRule.Comparison -> field == SmartPlaylistRuleField.EXPLICIT_RATING
    is SmartPlaylistRule.Reference -> false
    is SmartPlaylistRule.Group -> children.any { it.needsExplicitRating() }
    is SmartPlaylistRule.Not -> child.needsExplicitRating()
}

internal class CanonicalRuleBasedSmartPlaylistPreview(
    private val availability: CanonicalLibraryAvailabilityReader,
    private val catalog: CanonicalCatalogDao,
    private val ratings: ExplicitRecordingRatingReader,
    private val listening: ListeningBehaviorReader,
    private val planner: RuleBasedSmartPlaylistPlanner = RuleBasedSmartPlaylistPlanner(),
    private val remoteRatings: RemoteSmartPlaylistRatingReader = RemoteSmartPlaylistRatingReader {
        RemoteSmartPlaylistRatingSnapshot(complete = false, evidence = emptyList())
    },
    private val batchReader: SmartPlaylistBatchEvidenceReader? = null,
    private val v2Planner: SmartPlaylistV2RuleEngine = SmartPlaylistV2RuleEngine(),
    private val savedReferences: suspend () -> List<SavedSmartPlaylistV2Definition> = { emptyList() },
) {
    suspend fun preview(query: RuleBasedSmartPlaylistQuery): List<RuleBasedSmartPlaylistItem> {
        // V1 is still readable after the V2 migration. The selective Room
        // reader must fetch its rating/completion sort keys and every field
        // needed by the legacy rules, not an empty evidence projection.
        val fields = buildSet {
            add(SmartPlaylistRuleField.EXPLICIT_RATING)
            add(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS)
            if (query.minimumDaysSinceAudible != null || query.maximumDaysSinceAudible != null) {
                add(SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE)
            }
        }
        return planner.preview(candidates(requiredFields = fields), query)
    }

    suspend fun preview(
        query: SmartPlaylistV2Query,
        ownerId: String? = null,
    ): List<SmartPlaylistV2Match> {
        // Resolve a single bounded DAG first; missing/cyclic definitions fail the
        // entire preview, including under NOT/ANY, instead of returning false.
        val graph = if (query.rules.referencedPlaylistIds().isEmpty()) emptyList()
            else resolveSmartPlaylistReferences(query, savedReferences(), ownerId)
        val queries = graph.map { it.query } + query
        val needsRemote = queries.any { q ->
            q.rules.needsExplicitRating() || q.sort == SmartPlaylistV2Sort.RATING_ASC ||
                q.sort == SmartPlaylistV2Sort.RATING_DESC
        }
        val fields = queries.flatMap { it.requiredEvidenceFields() }.toSet()
        val rows = candidates(
            includeRemoteRatings = needsRemote,
            includeSourceEvidence = queries.any { it.rules.needsSourceKinds() },
            requiredFields = fields,
        )
        // A referenced playlist's ordering and limit are applied to the SAME
        // account-scoped candidate snapshot; Playback re-runs this on tap.
        return previewSmartPlaylistReferences(rows, query, graph, v2Planner)
    }

    /** One account-scoped snapshot, then 400-id chunks with bounded read pressure. */
    private suspend fun candidates(
        includeRemoteRatings: Boolean = true,
        includeSourceEvidence: Boolean = false,
        requiredFields: Set<SmartPlaylistRuleField> = emptySet(),
    ): List<RuleBasedRecordingCandidate> {
        val sourceMap = if (includeSourceEvidence) {
            availability.availableRecordingSources().associateBy { it.recordingId }
        } else emptyMap()
        // Source-aware queries use ONE scoped availability snapshot, never
        // cross-reference IDs from a different configured account.
        val ids = if (includeSourceEvidence) sourceMap.keys.sorted()
            else availability.availableRecordingIds().distinct().sorted()
        if (ids.any(String::isBlank)) return emptyList()
        val remoteSnapshot = if (includeRemoteRatings) remoteRatings.read() else null
        val remoteByRecording = if (remoteSnapshot?.complete == true) {
            remoteSnapshot.evidence.filter { it.recordingId.isNotBlank() }
                .groupBy { it.recordingId }
        } else emptyMap()

        // The fallback is for existing test adapters only. Production always
        // passes a RoomSmartPlaylistBatchEvidenceReader with three indexed
        // queries per batch; there is no per-track DAO loop in that path.
        val rows = if (batchReader != null) {
            ids.chunked(400).flatMap { chunk ->
                if (batchReader is RoomSmartPlaylistBatchEvidenceReader) {
                    batchReader.read(chunk, requiredFields)
                } else batchReader.read(chunk)
            }
        } else {
            ids.mapNotNull { id ->
                val recording = catalog.recordingById(id) ?: return@mapNotNull null
                val summary = listening.behaviorSummary(id)
                val audibleAt = listening.recentEventsForRecording(id).asSequence()
                    .filter { it.type == ListeningEventType.PLAYBACK_COMPLETED &&
                        (it.listenedDurationMs ?: 0L) > 0L }
                    .map { it.occurredAtEpochMs }.maxOrNull()
                RuleBasedRecordingCandidate(
                    recordingId = recording.id,
                    explicitRating = ratings.currentRating(id)?.value,
                    audibleCompletions = summary.completions,
                    lastAudibleAtEpochMs = audibleAt,
                    durationMs = recording.durationMs,
                    available = true,
                    title = recording.title,
                    artistDisplay = recording.artistDisplay,
                    earlySkips = summary.earlySkips,
                    lateSkips = summary.lateSkips,
                    releaseTitles = catalog.recordingReleaseTitlesByIds(listOf(id))
                        .map { it.releaseTitle }.distinct().sorted(),
                )
            }
        }
        val sourceBound = if (includeSourceEvidence) rows.map { row ->
            val source = sourceMap[row.recordingId]
            row.copy(
                sourceKinds = source?.kinds?.map { kind ->
                    when (kind) {
                        com.music.bitchord.data.catalog.CanonicalLibrarySourceKind.LOCAL ->
                            SmartPlaylistSourceKind.LOCAL
                        com.music.bitchord.data.catalog.CanonicalLibrarySourceKind.OPENSUBSONIC ->
                            SmartPlaylistSourceKind.OPENSUBSONIC
                    }
                }?.toSet(),
                sourceKindsComplete = source?.kindsComplete == true,
            )
        } else rows
        if (!includeRemoteRatings) return sourceBound
        return sourceBound.map { row ->
            val remote = remoteByRecording[row.recordingId].orEmpty()
            val resolved = effectiveSmartPlaylistRating(row.explicitRating, remote)
            // A missing value can be queried explicitly; conflicting,
            // invalid or not-yet-loaded remote evidence is UNKNOWN instead.
            // NOT(UNKNOWN) remains UNKNOWN in the V2 evaluator.
            val ambiguous = resolved == null && (
                row.explicitRating != null ||
                    remote.any { it.kind == RemoteSmartPlaylistRatingKind.VALUE ||
                        it.kind == RemoteSmartPlaylistRatingKind.INVALID }
                )
            row.copy(
                explicitRating = resolved,
                ratingEvidenceUnknown = ambiguous ||
                    (remoteSnapshot?.complete != true && row.explicitRating == null),
            )
        }
    }
}
