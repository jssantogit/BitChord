package com.music.bitchord.data.listening

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * V2 rule language is independent of provider IDs, Room entities and Media3.
 * It evaluates only canonical, already-authorized Recording evidence.
 */
@Serializable
internal sealed interface SmartPlaylistRule {
    @Serializable
    @SerialName("group")
    data class Group(
        val join: SmartPlaylistRuleJoin,
        val children: List<SmartPlaylistRule>,
    ) : SmartPlaylistRule

    @Serializable
    @SerialName("comparison")
    data class Comparison(
        val field: SmartPlaylistRuleField,
        val operator: SmartPlaylistRuleOperator,
        val value: Double? = null,
        val textValue: String? = null,
    ) : SmartPlaylistRule

    @Serializable
    @SerialName("not")
    data class Not(val child: SmartPlaylistRule) : SmartPlaylistRule

    /** A saved V2 definition ID, never a title or provider/native playlist key. */
    @Serializable
    @SerialName("reference")
    data class Reference(val playlistId: String) : SmartPlaylistRule
}

@Serializable
internal enum class SmartPlaylistRuleJoin { ALL, ANY }

@Serializable
internal enum class SmartPlaylistRuleField {
    EXPLICIT_RATING,
    AUDIBLE_COMPLETIONS,
    DAYS_SINCE_LAST_AUDIBLE,
    DURATION_MS,
    TITLE,
    ARTIST_DISPLAY,
    EARLY_SKIPS,
    LATE_SKIPS,
    RELEASE_TITLE,
    SOURCE_KIND,
    RELEASE_YEAR,
    ALBUM_ARTIST,
    TRACK_NUMBER,
    TRACK_ARTIST,
    DAYS_SINCE_LOCAL_RATING,
    RELEASE_ARTWORK_REFERENCE,
    DAYS_SINCE_LOCAL_FILE_ADDED,
}

internal enum class SmartPlaylistSourceKind { LOCAL, OPENSUBSONIC }

@Serializable
internal enum class SmartPlaylistRuleOperator {
    EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
    IS_PRESENT, IS_MISSING,
    CONTAINS, DOES_NOT_CONTAIN, STARTS_WITH, ENDS_WITH,
}

@Serializable
internal enum class SmartPlaylistV2Sort {
    RATING_DESC, RATING_ASC,
    COMPLETIONS_DESC, COMPLETIONS_ASC,
    DURATION_DESC, DURATION_ASC,
    LAST_AUDIBLE_DESC, LAST_AUDIBLE_ASC,
    RECORDING_ID_ASC, STABLE_RANDOM,
    TITLE_ASC, TITLE_DESC, ARTIST_ASC, ARTIST_DESC,
    LOCAL_RATING_NEWEST, LOCAL_RATING_OLDEST,
}

internal fun SmartPlaylistRuleField.isTextField(): Boolean =
    this == SmartPlaylistRuleField.TITLE ||
        this == SmartPlaylistRuleField.ARTIST_DISPLAY ||
        this == SmartPlaylistRuleField.RELEASE_TITLE ||
        this == SmartPlaylistRuleField.SOURCE_KIND ||
        this == SmartPlaylistRuleField.ALBUM_ARTIST ||
        this == SmartPlaylistRuleField.TRACK_ARTIST

internal fun SmartPlaylistRuleField.allowedOperators(): List<SmartPlaylistRuleOperator> =
    if (this == SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE) listOf(
        SmartPlaylistRuleOperator.IS_PRESENT,
    ) else if (this == SmartPlaylistRuleField.RELEASE_YEAR ||
        this == SmartPlaylistRuleField.TRACK_NUMBER ||
        this == SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED) listOf(
        SmartPlaylistRuleOperator.EQUAL,
        SmartPlaylistRuleOperator.NOT_EQUAL,
        SmartPlaylistRuleOperator.GREATER_THAN,
        SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
        SmartPlaylistRuleOperator.LESS_THAN,
        SmartPlaylistRuleOperator.LESS_OR_EQUAL,
        SmartPlaylistRuleOperator.IS_PRESENT,
    ) else if (this == SmartPlaylistRuleField.ALBUM_ARTIST ||
        this == SmartPlaylistRuleField.TRACK_ARTIST) listOf(
        SmartPlaylistRuleOperator.EQUAL,
        SmartPlaylistRuleOperator.NOT_EQUAL,
        SmartPlaylistRuleOperator.CONTAINS,
        SmartPlaylistRuleOperator.DOES_NOT_CONTAIN,
        SmartPlaylistRuleOperator.STARTS_WITH,
        SmartPlaylistRuleOperator.ENDS_WITH,
        SmartPlaylistRuleOperator.IS_PRESENT,
    ) else if (this == SmartPlaylistRuleField.SOURCE_KIND) listOf(
        SmartPlaylistRuleOperator.EQUAL,
        SmartPlaylistRuleOperator.NOT_EQUAL,
    ) else if (isTextField()) listOf(
        SmartPlaylistRuleOperator.CONTAINS,
        SmartPlaylistRuleOperator.DOES_NOT_CONTAIN,
        SmartPlaylistRuleOperator.EQUAL,
        SmartPlaylistRuleOperator.NOT_EQUAL,
        SmartPlaylistRuleOperator.STARTS_WITH,
        SmartPlaylistRuleOperator.ENDS_WITH,
        SmartPlaylistRuleOperator.IS_PRESENT,
        SmartPlaylistRuleOperator.IS_MISSING,
    ) else listOf(
        SmartPlaylistRuleOperator.EQUAL,
        SmartPlaylistRuleOperator.NOT_EQUAL,
        SmartPlaylistRuleOperator.GREATER_THAN,
        SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
        SmartPlaylistRuleOperator.LESS_THAN,
        SmartPlaylistRuleOperator.LESS_OR_EQUAL,
        SmartPlaylistRuleOperator.IS_PRESENT,
        SmartPlaylistRuleOperator.IS_MISSING,
    )

private fun String?.normalizedSmartPlaylistText(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)


/**
 * Query validation is strict, but a missing piece of candidate evidence is not
 * interpreted as a rule validation error or silently replaced with zero.
 */
@Serializable
internal data class SmartPlaylistV2Query(
    val rules: SmartPlaylistRule,
    val sort: SmartPlaylistV2Sort = SmartPlaylistV2Sort.RATING_DESC,
    val limit: Int = 100,
    val randomSeed: Int = 0,
) {
    init {
        require(limit in 1..10_000) { "Invalid result limit" }
        var count = 0
        fun validate(node: SmartPlaylistRule, depth: Int) {
            require(depth <= 8) { "Rule nesting exceeds safe depth" }
            count++
            require(count <= 128) { "Too many smart playlist rules" }
            when (node) {
                is SmartPlaylistRule.Group -> {
                    require(node.children.isNotEmpty()) { "An empty group cannot match" }
                    node.children.forEach { validate(it, depth + 1) }
                }
                is SmartPlaylistRule.Not -> validate(node.child, depth + 1)
                is SmartPlaylistRule.Reference -> {
                    require(node.playlistId.isNotBlank() &&
                        node.playlistId.length <= 100 && node.playlistId == node.playlistId.trim()) {
                        "Invalid saved smart playlist reference ID"
                    }
                }
                is SmartPlaylistRule.Comparison -> {
                    val presence = node.operator == SmartPlaylistRuleOperator.IS_PRESENT ||
                        node.operator == SmartPlaylistRuleOperator.IS_MISSING
                    require(node.operator in node.field.allowedOperators()) {
                        "Operator cannot be applied to field type"
                    }
                    if (node.field.isTextField()) {
                        require(node.value == null) { "Text rules cannot carry numeric values" }
                        if (presence) {
                            require(node.textValue == null) { "Presence tests must not carry a value" }
                        } else {
                            require(node.textValue != null &&
                                node.textValue.isNotBlank() &&
                                node.textValue.trim().length <= 160) {
                                "Text comparison requires a nonblank, bounded value"
                            }
                            if (node.field == SmartPlaylistRuleField.SOURCE_KIND) {
                                require(node.textValue.trim().lowercase(Locale.ROOT) in
                                    setOf("local", "opensubsonic")) {
                                    "Source selection must use an allowed, scoped source category"
                                }
                            }
                        }
                    } else {
                        require(node.textValue == null) { "Numeric rules cannot carry text" }
                        if (presence) {
                            require(node.value == null) { "Presence tests must not carry a value" }
                        } else {
                            require(node.value?.isFinite() == true) {
                                "Comparison requires a finite value"
                            }
                        }
                        if (node.field == SmartPlaylistRuleField.RELEASE_YEAR &&
                            !presence) {
                            require(node.value!! in 1000.0..9999.0 &&
                                node.value % 1.0 == 0.0) {
                                "Release year must be a four-digit calendar year"
                            }
                        }
                        if (node.field in listOf(
                            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING,
                            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
                        ) && !presence) {
                            require(node.value!! in 0.0..100_000.0 &&
                                node.value % 1.0 == 0.0) {
                                "Days since local rating must be a nonnegative whole number"
                            }
                        }
                        if (node.field == SmartPlaylistRuleField.TRACK_NUMBER && !presence) {
                            require(node.value!! in 1.0..99_999.0 &&
                                node.value % 1.0 == 0.0) {
                                "Track position must be a positive integer"
                            }
                        }
                        if (node.field in listOf(
                            SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                            SmartPlaylistRuleField.EARLY_SKIPS,
                            SmartPlaylistRuleField.LATE_SKIPS,
                        ) && !presence) {
                            require(node.value!! >= 0.0 && node.value % 1.0 == 0.0) {
                                "Completion count must be a nonnegative integer"
                            }
                        }
                    }
                }
            }
        }
        validate(rules, 0)
    }
}

internal data class SmartPlaylistV2Match(
    val recordingId: String,
    val explicitRating: Double?,
    val confirmedCompletions: Long,
)

/** A non-match due to missing evidence is NOT proven exclusion from a child playlist. */
internal data class SmartPlaylistReferencedMembership(
    val included: Set<String>,
    val unknown: Set<String>,
)

internal data class SmartPlaylistV2Evaluation(
    val matches: List<SmartPlaylistV2Match>,
    val unknownRecordingIds: Set<String>,
)

/**
 * Normalize each canonical display string once, rather than on each
 * comparator invocation (O(N log N) repeated allocations at 50k entries).
 * Missing metadata always sorts last; RecordingId always breaks ties.
 */
private fun sortByNormalizedText(
    rows: List<RuleBasedRecordingCandidate>,
    select: (RuleBasedRecordingCandidate) -> String?,
    descending: Boolean,
): List<RuleBasedRecordingCandidate> {
    data class WithKey(val row: RuleBasedRecordingCandidate, val key: String?)
    val keyed = rows.map { WithKey(it, select(it).normalizedSmartPlaylistText()) }
    return keyed.sortedWith { a, b ->
        val missing = (a.key == null).compareTo(b.key == null)
        if (missing != 0) missing
        else {
            val text = (a.key ?: "").compareTo(b.key ?: "")
            val compared = if (descending) -text else text
            if (compared != 0) compared else a.row.recordingId.compareTo(b.row.recordingId)
        }
    }.map { it.row }
}

private enum class RuleTruth { TRUE, FALSE, UNKNOWN }

/**
 * A three-valued truth model is critical here: NOT UNKNOWN stays UNKNOWN.
 * UNKNOWN never includes media in the output, even within nested disjunctions
 * unless another branch has actually satisfied the rule.
 */
internal class SmartPlaylistV2RuleEngine(
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    fun preview(
        input: List<RuleBasedRecordingCandidate>,
        query: SmartPlaylistV2Query,
        referenceMemberships: Map<String, SmartPlaylistReferencedMembership> = emptyMap(),
    ): List<SmartPlaylistV2Match> =
        evaluateRows(input, query, referenceMemberships, captureUnknowns = false).matches

    /** Child queries record UNKNOWN, whereas ordinary root preview skips that memory. */
    internal fun evaluateWithUnknowns(
        input: List<RuleBasedRecordingCandidate>,
        query: SmartPlaylistV2Query,
        referenceMemberships: Map<String, SmartPlaylistReferencedMembership> = emptyMap(),
    ): SmartPlaylistV2Evaluation =
        evaluateRows(input, query, referenceMemberships, captureUnknowns = true)

    private fun evaluateRows(
        input: List<RuleBasedRecordingCandidate>,
        query: SmartPlaylistV2Query,
        referenceMemberships: Map<String, SmartPlaylistReferencedMembership>,
        captureUnknowns: Boolean,
    ): SmartPlaylistV2Evaluation {
        val now = nowEpochMs()
        if (now < 0) return SmartPlaylistV2Evaluation(emptyList(), emptySet())
        val unknown = linkedSetOf<String>()
        // Keep one candidate per RecordingId without allocating a List for each
        // of 50k+ recordings. Once conflicting evidence appears, reject ALL
        // versions of that ID, including later repeats of the first candidate.
        val uniqueById = LinkedHashMap<String, RuleBasedRecordingCandidate>()
        val conflictingIds = HashSet<String>()
        for (item in input) {
            val id = item.recordingId
            if (id in conflictingIds) continue
            val previous = uniqueById[id]
            when {
                previous == null -> uniqueById[id] = item
                previous != item -> {
                    uniqueById.remove(id)
                    conflictingIds += id
                }
            }
        }
        val candidateRows = uniqueById.values.asSequence()
            .mapNotNull { item ->
                if (item.recordingId.isBlank() ||
                    !item.available || item.audibleCompletions < 0 ||
                    item.earlySkips < 0 || item.lateSkips < 0 ||
                    (item.explicitRating != null &&
                        (!item.explicitRating.isFinite() ||
                            item.explicitRating !in 1.0..5.0)) ||
                    (item.lastAudibleAtEpochMs != null &&
                        (item.lastAudibleAtEpochMs < 0 || item.lastAudibleAtEpochMs > now))
                ) return@mapNotNull null
                when (evaluate(query.rules, item, now, referenceMemberships)) {
                    RuleTruth.TRUE -> item
                    RuleTruth.FALSE -> null
                    RuleTruth.UNKNOWN -> {
                        if (captureUnknowns) unknown += item.recordingId
                        null
                    }
                }
            }.toList()
        val sorted = when (query.sort) {
            SmartPlaylistV2Sort.RATING_DESC -> candidateRows.sortedWith(
                compareByDescending<RuleBasedRecordingCandidate> { it.explicitRating ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.audibleCompletions }.thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.RATING_ASC -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> { it.explicitRating ?: Double.POSITIVE_INFINITY }
                    .thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.COMPLETIONS_DESC -> candidateRows.sortedWith(
                compareByDescending<RuleBasedRecordingCandidate> { it.audibleCompletions }
                    .thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.COMPLETIONS_ASC -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> { it.audibleCompletions }
                    .thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.DURATION_DESC -> candidateRows.sortedWith(
                compareByDescending<RuleBasedRecordingCandidate> { it.durationMs ?: Long.MIN_VALUE }
                    .thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.DURATION_ASC -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> { it.durationMs ?: Long.MAX_VALUE }
                    .thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.LAST_AUDIBLE_DESC -> candidateRows.sortedWith(
                compareByDescending<RuleBasedRecordingCandidate> { it.lastAudibleAtEpochMs ?: Long.MIN_VALUE }
                    .thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.LAST_AUDIBLE_ASC -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> { it.lastAudibleAtEpochMs ?: Long.MAX_VALUE }
                    .thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.TITLE_ASC ->
                sortByNormalizedText(candidateRows, { it.title }, descending = false)
            SmartPlaylistV2Sort.TITLE_DESC ->
                sortByNormalizedText(candidateRows, { it.title }, descending = true)
            SmartPlaylistV2Sort.ARTIST_ASC ->
                sortByNormalizedText(candidateRows, { it.artistDisplay }, descending = false)
            SmartPlaylistV2Sort.ARTIST_DESC ->
                sortByNormalizedText(candidateRows, { it.artistDisplay }, descending = true)
            SmartPlaylistV2Sort.LOCAL_RATING_NEWEST -> candidateRows.sortedWith(
                compareByDescending<RuleBasedRecordingCandidate> {
                    it.localRatingUpdatedAtEpochMs?.takeIf { timestamp ->
                        it.localRatingDateEvidenceKnown && timestamp in 0L..now
                    } ?: Long.MIN_VALUE
                }.thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.LOCAL_RATING_OLDEST -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> {
                    it.localRatingUpdatedAtEpochMs?.takeIf { timestamp ->
                        it.localRatingDateEvidenceKnown && timestamp in 0L..now
                    } ?: Long.MAX_VALUE
                }.thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.RECORDING_ID_ASC -> candidateRows.sortedBy { it.recordingId }
            SmartPlaylistV2Sort.STABLE_RANDOM -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> { it.recordingId.hashCode() xor query.randomSeed }
                    .thenBy { it.recordingId },
            )
        }
        val matches = sorted.asSequence().take(query.limit).map {
            SmartPlaylistV2Match(it.recordingId, it.explicitRating, it.audibleCompletions)
        }.toList()
        return SmartPlaylistV2Evaluation(matches, unknown)

    }

    private fun evaluate(
        rule: SmartPlaylistRule,
        row: RuleBasedRecordingCandidate,
        now: Long,
        referenceMemberships: Map<String, SmartPlaylistReferencedMembership>,
    ): RuleTruth = when (rule) {
        is SmartPlaylistRule.Group -> when (rule.join) {
            SmartPlaylistRuleJoin.ALL -> {
                // Avoid one temporary child-truth List per Recording for large
                // libraries; FALSE dominates UNKNOWN and ends evaluation.
                var foundFalse = false
                var foundUnknown = false
                for (child in rule.children) {
                    when (evaluate(child, row, now, referenceMemberships)) {
                        RuleTruth.FALSE -> { foundFalse = true; break }
                        RuleTruth.UNKNOWN -> foundUnknown = true
                        RuleTruth.TRUE -> Unit
                    }
                }
                when {
                    foundFalse -> RuleTruth.FALSE
                    foundUnknown -> RuleTruth.UNKNOWN
                    else -> RuleTruth.TRUE
                }
            }
            SmartPlaylistRuleJoin.ANY -> {
                // TRUE dominates UNKNOWN; preserve unknown when nothing matches.
                var foundTrue = false
                var foundUnknown = false
                for (child in rule.children) {
                    when (evaluate(child, row, now, referenceMemberships)) {
                        RuleTruth.TRUE -> { foundTrue = true; break }
                        RuleTruth.UNKNOWN -> foundUnknown = true
                        RuleTruth.FALSE -> Unit
                    }
                }
                when {
                    foundTrue -> RuleTruth.TRUE
                    foundUnknown -> RuleTruth.UNKNOWN
                    else -> RuleTruth.FALSE
                }
            }
        }
        is SmartPlaylistRule.Not -> when (evaluate(rule.child, row, now, referenceMemberships)) {
            RuleTruth.TRUE -> RuleTruth.FALSE
            RuleTruth.FALSE -> RuleTruth.TRUE
            RuleTruth.UNKNOWN -> RuleTruth.UNKNOWN
        }
        is SmartPlaylistRule.Reference -> referenceMemberships[rule.playlistId]?.let { membership ->
            when (row.recordingId) {
                in membership.included -> RuleTruth.TRUE
                in membership.unknown -> RuleTruth.UNKNOWN
                else -> RuleTruth.FALSE
            }
        } ?: RuleTruth.UNKNOWN
        is SmartPlaylistRule.Comparison -> compare(rule, row, now)
    }

    private fun compare(
        rule: SmartPlaylistRule.Comparison,
        row: RuleBasedRecordingCandidate,
        now: Long,
    ): RuleTruth {
        if (rule.field == SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED) {
            // A Recording may have multiple local MediaStore assets. No remote
            // asset or unconfigured local source may satisfy this predicate.
            if (SmartPlaylistSourceKind.LOCAL !in row.sourceKinds.orEmpty()) return RuleTruth.UNKNOWN
            val valid = row.localAddedEpochMs.filter { it in 0L..now }
            val incomplete = row.localAddedDateEvidenceUnknown ||
                valid.size != row.localAddedEpochMs.size
            if (rule.operator == SmartPlaylistRuleOperator.IS_PRESENT) {
                return if (valid.isNotEmpty()) RuleTruth.TRUE else RuleTruth.UNKNOWN
            }
            if (valid.isEmpty()) return RuleTruth.UNKNOWN
            val expected = rule.value ?: return RuleTruth.UNKNOWN
            val anyMatching = valid.any { timestamp ->
                val age = ((now - timestamp) / 86_400_000L).toDouble()
                when (rule.operator) {
                    SmartPlaylistRuleOperator.EQUAL,
                    SmartPlaylistRuleOperator.NOT_EQUAL -> age == expected
                    SmartPlaylistRuleOperator.GREATER_THAN -> age > expected
                    SmartPlaylistRuleOperator.GREATER_OR_EQUAL -> age >= expected
                    SmartPlaylistRuleOperator.LESS_THAN -> age < expected
                    SmartPlaylistRuleOperator.LESS_OR_EQUAL -> age <= expected
                    else -> return RuleTruth.UNKNOWN
                }
            }
            if (rule.operator == SmartPlaylistRuleOperator.NOT_EQUAL) {
                return when {
                    anyMatching -> RuleTruth.FALSE
                    incomplete -> RuleTruth.UNKNOWN
                    else -> RuleTruth.TRUE
                }
            }
            return when {
                anyMatching -> RuleTruth.TRUE
                incomplete -> RuleTruth.UNKNOWN
                else -> RuleTruth.FALSE
            }
        }
        if (rule.field == SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE) {
            return if (row.hasReleaseArtworkReference) RuleTruth.TRUE else RuleTruth.UNKNOWN
        }
        if (rule.field == SmartPlaylistRuleField.RELEASE_YEAR ||
            rule.field == SmartPlaylistRuleField.TRACK_NUMBER) {
            val numbers = if (rule.field == SmartPlaylistRuleField.RELEASE_YEAR)
                row.releaseYears else row.trackNumbers
            val evidenceUnknown = if (rule.field == SmartPlaylistRuleField.RELEASE_YEAR)
                row.releaseYearEvidenceUnknown else row.trackNumberEvidenceUnknown
            if (rule.operator == SmartPlaylistRuleOperator.IS_PRESENT) {
                return if (numbers.isNotEmpty()) RuleTruth.TRUE else RuleTruth.UNKNOWN
            }
            if (numbers.isEmpty()) return RuleTruth.UNKNOWN
            val target = rule.value ?: return RuleTruth.UNKNOWN
            val anyMatching = numbers.any { number ->
                val y = number.toDouble()
                when (rule.operator) {
                    SmartPlaylistRuleOperator.EQUAL, SmartPlaylistRuleOperator.NOT_EQUAL -> y == target
                    SmartPlaylistRuleOperator.GREATER_THAN -> y > target
                    SmartPlaylistRuleOperator.GREATER_OR_EQUAL -> y >= target
                    SmartPlaylistRuleOperator.LESS_THAN -> y < target
                    SmartPlaylistRuleOperator.LESS_OR_EQUAL -> y <= target
                    else -> return RuleTruth.UNKNOWN
                }
            }
            if (rule.operator == SmartPlaylistRuleOperator.NOT_EQUAL) {
                return when {
                    anyMatching -> RuleTruth.FALSE
                    evidenceUnknown -> RuleTruth.UNKNOWN
                    else -> RuleTruth.TRUE
                }
            }
            return when {
                anyMatching -> RuleTruth.TRUE
                evidenceUnknown -> RuleTruth.UNKNOWN
                else -> RuleTruth.FALSE
            }
        }
        if (rule.field == SmartPlaylistRuleField.ALBUM_ARTIST ||
            rule.field == SmartPlaylistRuleField.TRACK_ARTIST) {
            val creditedNames = if (rule.field == SmartPlaylistRuleField.ALBUM_ARTIST)
                row.albumArtistNames else row.trackArtistNames
            val evidenceUnknown = if (rule.field == SmartPlaylistRuleField.ALBUM_ARTIST)
                row.albumArtistEvidenceUnknown else row.trackArtistEvidenceUnknown
            val names = creditedNames.mapNotNull {
                it.normalizedSmartPlaylistText()
            }
            if (rule.operator == SmartPlaylistRuleOperator.IS_PRESENT) {
                return if (names.isNotEmpty()) RuleTruth.TRUE else RuleTruth.UNKNOWN
            }
            val wanted = rule.textValue.normalizedSmartPlaylistText()
                ?: return RuleTruth.UNKNOWN
            if (names.isEmpty()) return RuleTruth.UNKNOWN
            val match = names.any { name ->
                when (rule.operator) {
                    SmartPlaylistRuleOperator.EQUAL,
                    SmartPlaylistRuleOperator.NOT_EQUAL -> name == wanted
                    SmartPlaylistRuleOperator.CONTAINS,
                    SmartPlaylistRuleOperator.DOES_NOT_CONTAIN -> name.contains(wanted)
                    SmartPlaylistRuleOperator.STARTS_WITH -> name.startsWith(wanted)
                    SmartPlaylistRuleOperator.ENDS_WITH -> name.endsWith(wanted)
                    else -> return RuleTruth.UNKNOWN
                }
            }
            if (rule.operator == SmartPlaylistRuleOperator.NOT_EQUAL ||
                rule.operator == SmartPlaylistRuleOperator.DOES_NOT_CONTAIN) {
                return when {
                    match -> RuleTruth.FALSE
                    evidenceUnknown -> RuleTruth.UNKNOWN
                    else -> RuleTruth.TRUE
                }
            }
            return when {
                match -> RuleTruth.TRUE
                evidenceUnknown -> RuleTruth.UNKNOWN
                else -> RuleTruth.FALSE
            }
        }
        if (rule.field == SmartPlaylistRuleField.SOURCE_KIND) {
            val availableKinds = row.sourceKinds ?: return RuleTruth.UNKNOWN
            if (availableKinds.isEmpty()) return RuleTruth.UNKNOWN
            val requested = when (rule.textValue?.trim()?.lowercase(Locale.ROOT)) {
                "local" -> SmartPlaylistSourceKind.LOCAL
                "opensubsonic" -> SmartPlaylistSourceKind.OPENSUBSONIC
                else -> return RuleTruth.UNKNOWN
            }
            val matching = requested in availableKinds
            return when (rule.operator) {
                SmartPlaylistRuleOperator.EQUAL ->
                    if (matching) RuleTruth.TRUE else RuleTruth.FALSE
                SmartPlaylistRuleOperator.NOT_EQUAL ->
                    if (matching) RuleTruth.FALSE else RuleTruth.TRUE
                else -> RuleTruth.UNKNOWN
            }
        }
        if (rule.field.isTextField()) {
            // Only a verified positive title/artist field is known. A null or
            // blank canonical label does not prove that metadata is missing.
            // Linked Release names can be partially unknown; negative
            // predicates require ALL linked names to be checked.
            val rawValues = when (rule.field) {
                SmartPlaylistRuleField.TITLE -> listOfNotNull(row.title)
                SmartPlaylistRuleField.ARTIST_DISPLAY -> listOfNotNull(row.artistDisplay)
                SmartPlaylistRuleField.RELEASE_TITLE -> row.releaseTitles
                SmartPlaylistRuleField.ALBUM_ARTIST,
                SmartPlaylistRuleField.TRACK_ARTIST,
                SmartPlaylistRuleField.SOURCE_KIND -> emptyList()
                else -> emptyList()
            }
            val values = rawValues.mapNotNull {
                it.normalizedSmartPlaylistText()
            }.distinct()
            val incomplete = rawValues.size != rawValues.count {
                it.normalizedSmartPlaylistText() != null
            }
            if ((rule.field == SmartPlaylistRuleField.TITLE ||
                    rule.field == SmartPlaylistRuleField.ARTIST_DISPLAY) &&
                values.isEmpty()
            ) return RuleTruth.UNKNOWN

            when (rule.operator) {
                SmartPlaylistRuleOperator.IS_PRESENT -> return when {
                    values.isNotEmpty() -> RuleTruth.TRUE
                    incomplete -> RuleTruth.UNKNOWN
                    else -> RuleTruth.FALSE
                }
                SmartPlaylistRuleOperator.IS_MISSING -> return when {
                    values.isNotEmpty() -> RuleTruth.FALSE
                    incomplete -> RuleTruth.UNKNOWN
                    else -> RuleTruth.TRUE
                }
                else -> Unit
            }
            val expected = rule.textValue.normalizedSmartPlaylistText()
                ?: return RuleTruth.UNKNOWN
            if (values.isEmpty()) return RuleTruth.UNKNOWN
            val matched = values.any { actual ->
                when (rule.operator) {
                    SmartPlaylistRuleOperator.EQUAL,
                    SmartPlaylistRuleOperator.NOT_EQUAL -> actual == expected
                    SmartPlaylistRuleOperator.CONTAINS,
                    SmartPlaylistRuleOperator.DOES_NOT_CONTAIN -> actual.contains(expected)
                    SmartPlaylistRuleOperator.STARTS_WITH -> actual.startsWith(expected)
                    SmartPlaylistRuleOperator.ENDS_WITH -> actual.endsWith(expected)
                    else -> return RuleTruth.UNKNOWN
                }
            }
            val negative = rule.operator == SmartPlaylistRuleOperator.NOT_EQUAL ||
                rule.operator == SmartPlaylistRuleOperator.DOES_NOT_CONTAIN
            return when {
                matched -> if (negative) RuleTruth.FALSE else RuleTruth.TRUE
                incomplete -> RuleTruth.UNKNOWN
                negative -> RuleTruth.TRUE
                else -> RuleTruth.FALSE
            }
        }
        if (rule.field == SmartPlaylistRuleField.EXPLICIT_RATING &&
            row.ratingEvidenceUnknown
        ) return RuleTruth.UNKNOWN
        if (rule.field == SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING) {
            if (!row.localRatingDateEvidenceKnown) return RuleTruth.UNKNOWN
            val updated = row.localRatingUpdatedAtEpochMs
            if (updated != null && (updated < 0L || updated > now)) return RuleTruth.UNKNOWN
        }
        val value: Double? = when (rule.field) {
            SmartPlaylistRuleField.EXPLICIT_RATING -> row.explicitRating
            SmartPlaylistRuleField.AUDIBLE_COMPLETIONS -> row.audibleCompletions.toDouble()
            SmartPlaylistRuleField.DURATION_MS -> row.durationMs?.toDouble()
            SmartPlaylistRuleField.EARLY_SKIPS -> row.earlySkips.toDouble()
            SmartPlaylistRuleField.LATE_SKIPS -> row.lateSkips.toDouble()
            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING ->
                row.localRatingUpdatedAtEpochMs?.let { ((now - it) / 86_400_000L).toDouble() }
            SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE ->
                if (row.audibleCompletions > 0) {
                    row.lastAudibleAtEpochMs?.let { ((now - it) / 86_400_000L).toDouble() }
                } else null
            SmartPlaylistRuleField.TITLE, SmartPlaylistRuleField.ARTIST_DISPLAY,
            SmartPlaylistRuleField.RELEASE_TITLE, SmartPlaylistRuleField.SOURCE_KIND,
            SmartPlaylistRuleField.RELEASE_YEAR,
            SmartPlaylistRuleField.ALBUM_ARTIST,
            SmartPlaylistRuleField.TRACK_NUMBER,
            SmartPlaylistRuleField.TRACK_ARTIST,
            SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE,
            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED -> null
        }
        if (rule.field == SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE &&
            row.audibleCompletions > 0 && row.lastAudibleAtEpochMs == null
        ) return RuleTruth.UNKNOWN

        return when (rule.operator) {
            SmartPlaylistRuleOperator.IS_PRESENT ->
                if (value != null) RuleTruth.TRUE else RuleTruth.FALSE
            SmartPlaylistRuleOperator.IS_MISSING ->
                if (value == null) RuleTruth.TRUE else RuleTruth.FALSE
            else -> {
                if (value == null) return RuleTruth.UNKNOWN
                val expected = rule.value ?: return RuleTruth.UNKNOWN
                val result = when (rule.operator) {
                    SmartPlaylistRuleOperator.EQUAL -> value == expected
                    SmartPlaylistRuleOperator.NOT_EQUAL -> value != expected
                    SmartPlaylistRuleOperator.GREATER_THAN -> value > expected
                    SmartPlaylistRuleOperator.GREATER_OR_EQUAL -> value >= expected
                    SmartPlaylistRuleOperator.LESS_THAN -> value < expected
                    SmartPlaylistRuleOperator.LESS_OR_EQUAL -> value <= expected
                    else -> false
                }
                if (result) RuleTruth.TRUE else RuleTruth.FALSE
            }
        }
    }
}
