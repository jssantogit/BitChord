package com.music.bitchord.data.listening

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    ) : SmartPlaylistRule

    @Serializable
    @SerialName("not")
    data class Not(val child: SmartPlaylistRule) : SmartPlaylistRule
}

@Serializable
internal enum class SmartPlaylistRuleJoin { ALL, ANY }

@Serializable
internal enum class SmartPlaylistRuleField {
    EXPLICIT_RATING,
    AUDIBLE_COMPLETIONS,
    DAYS_SINCE_LAST_AUDIBLE,
    DURATION_MS,
}

@Serializable
internal enum class SmartPlaylistRuleOperator {
    EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL,
    IS_PRESENT, IS_MISSING,
}

@Serializable
internal enum class SmartPlaylistV2Sort {
    RATING_DESC, RATING_ASC,
    COMPLETIONS_DESC, COMPLETIONS_ASC,
    DURATION_DESC, DURATION_ASC,
    LAST_AUDIBLE_DESC, LAST_AUDIBLE_ASC,
    RECORDING_ID_ASC, STABLE_RANDOM,
}

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
                is SmartPlaylistRule.Comparison -> {
                    val presence = node.operator == SmartPlaylistRuleOperator.IS_PRESENT ||
                        node.operator == SmartPlaylistRuleOperator.IS_MISSING
                    if (presence) {
                        require(node.value == null) { "Presence tests must not carry a value" }
                    } else {
                        require(node.value?.isFinite() == true) { "Comparison requires a finite value" }
                    }
                    if (node.field == SmartPlaylistRuleField.AUDIBLE_COMPLETIONS &&
                        !presence) {
                        require(node.value!! >= 0.0 && node.value % 1.0 == 0.0) {
                            "Completion count must be a nonnegative integer"
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
    ): List<SmartPlaylistV2Match> {
        val now = nowEpochMs()
        if (now < 0) return emptyList()
        val candidateRows = input.groupBy { it.recordingId }.values.asSequence()
            .mapNotNull { grouped ->
                val item = grouped.firstOrNull() ?: return@mapNotNull null
                if (item.recordingId.isBlank() || grouped.any { it != item } ||
                    !item.available || item.audibleCompletions < 0 ||
                    (item.explicitRating != null &&
                        (!item.explicitRating.isFinite() ||
                            item.explicitRating !in 1.0..5.0)) ||
                    (item.lastAudibleAtEpochMs != null &&
                        (item.lastAudibleAtEpochMs < 0 || item.lastAudibleAtEpochMs > now))
                ) return@mapNotNull null
                if (evaluate(query.rules, item, now) == RuleTruth.TRUE) item else null
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
            SmartPlaylistV2Sort.RECORDING_ID_ASC -> candidateRows.sortedBy { it.recordingId }
            SmartPlaylistV2Sort.STABLE_RANDOM -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> { it.recordingId.hashCode() xor query.randomSeed }
                    .thenBy { it.recordingId },
            )
        }
        return sorted.asSequence().take(query.limit).map {
            SmartPlaylistV2Match(it.recordingId, it.explicitRating, it.audibleCompletions)
        }.toList()
    }

    private fun evaluate(
        rule: SmartPlaylistRule,
        row: RuleBasedRecordingCandidate,
        now: Long,
    ): RuleTruth = when (rule) {
        is SmartPlaylistRule.Group -> when (rule.join) {
            SmartPlaylistRuleJoin.ALL -> {
                val values = rule.children.map { evaluate(it, row, now) }
                when {
                    RuleTruth.FALSE in values -> RuleTruth.FALSE
                    RuleTruth.UNKNOWN in values -> RuleTruth.UNKNOWN
                    else -> RuleTruth.TRUE
                }
            }
            SmartPlaylistRuleJoin.ANY -> {
                val values = rule.children.map { evaluate(it, row, now) }
                when {
                    RuleTruth.TRUE in values -> RuleTruth.TRUE
                    RuleTruth.UNKNOWN in values -> RuleTruth.UNKNOWN
                    else -> RuleTruth.FALSE
                }
            }
        }
        is SmartPlaylistRule.Not -> when (evaluate(rule.child, row, now)) {
            RuleTruth.TRUE -> RuleTruth.FALSE
            RuleTruth.FALSE -> RuleTruth.TRUE
            RuleTruth.UNKNOWN -> RuleTruth.UNKNOWN
        }
        is SmartPlaylistRule.Comparison -> compare(rule, row, now)
    }

    private fun compare(
        rule: SmartPlaylistRule.Comparison,
        row: RuleBasedRecordingCandidate,
        now: Long,
    ): RuleTruth {
        if (rule.field == SmartPlaylistRuleField.EXPLICIT_RATING &&
            row.ratingEvidenceUnknown
        ) return RuleTruth.UNKNOWN
        val value: Double? = when (rule.field) {
            SmartPlaylistRuleField.EXPLICIT_RATING -> row.explicitRating
            SmartPlaylistRuleField.AUDIBLE_COMPLETIONS -> row.audibleCompletions.toDouble()
            SmartPlaylistRuleField.DURATION_MS -> row.durationMs?.toDouble()
            SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE ->
                if (row.audibleCompletions > 0) {
                    row.lastAudibleAtEpochMs?.let { ((now - it) / 86_400_000L).toDouble() }
                } else null
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
