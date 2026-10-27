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
}

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
}

internal fun SmartPlaylistRuleField.isTextField(): Boolean =
    this == SmartPlaylistRuleField.TITLE || this == SmartPlaylistRuleField.ARTIST_DISPLAY

internal fun SmartPlaylistRuleField.allowedOperators(): List<SmartPlaylistRuleOperator> =
    if (isTextField()) listOf(
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
                    item.earlySkips < 0 || item.lateSkips < 0 ||
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
            SmartPlaylistV2Sort.TITLE_ASC -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> { it.title.normalizedSmartPlaylistText() == null }
                    .thenBy { it.title.normalizedSmartPlaylistText() ?: "" }.thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.TITLE_DESC -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> { it.title.normalizedSmartPlaylistText() == null }
                    .thenByDescending { it.title.normalizedSmartPlaylistText() ?: "" }.thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.ARTIST_ASC -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> { it.artistDisplay.normalizedSmartPlaylistText() == null }
                    .thenBy { it.artistDisplay.normalizedSmartPlaylistText() ?: "" }.thenBy { it.recordingId },
            )
            SmartPlaylistV2Sort.ARTIST_DESC -> candidateRows.sortedWith(
                compareBy<RuleBasedRecordingCandidate> { it.artistDisplay.normalizedSmartPlaylistText() == null }
                    .thenByDescending { it.artistDisplay.normalizedSmartPlaylistText() ?: "" }.thenBy { it.recordingId },
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
        if (rule.field.isTextField()) {
            val actual = when (rule.field) {
                SmartPlaylistRuleField.TITLE -> row.title
                SmartPlaylistRuleField.ARTIST_DISPLAY -> row.artistDisplay
                else -> null
            }.normalizedSmartPlaylistText()
            val expected = rule.textValue.normalizedSmartPlaylistText()
            val matches = when (rule.operator) {
                SmartPlaylistRuleOperator.IS_PRESENT -> actual != null
                SmartPlaylistRuleOperator.IS_MISSING -> actual == null
                else -> {
                    if (actual == null || expected == null) return RuleTruth.UNKNOWN
                    when (rule.operator) {
                        SmartPlaylistRuleOperator.EQUAL -> actual == expected
                        SmartPlaylistRuleOperator.NOT_EQUAL -> actual != expected
                        SmartPlaylistRuleOperator.CONTAINS -> actual.contains(expected)
                        SmartPlaylistRuleOperator.DOES_NOT_CONTAIN -> !actual.contains(expected)
                        SmartPlaylistRuleOperator.STARTS_WITH -> actual.startsWith(expected)
                        SmartPlaylistRuleOperator.ENDS_WITH -> actual.endsWith(expected)
                        else -> return RuleTruth.UNKNOWN
                    }
                }
            }
            return if (matches) RuleTruth.TRUE else RuleTruth.FALSE
        }
        if (rule.field == SmartPlaylistRuleField.EXPLICIT_RATING &&
            row.ratingEvidenceUnknown
        ) return RuleTruth.UNKNOWN
        val value: Double? = when (rule.field) {
            SmartPlaylistRuleField.EXPLICIT_RATING -> row.explicitRating
            SmartPlaylistRuleField.AUDIBLE_COMPLETIONS -> row.audibleCompletions.toDouble()
            SmartPlaylistRuleField.DURATION_MS -> row.durationMs?.toDouble()
            SmartPlaylistRuleField.EARLY_SKIPS -> row.earlySkips.toDouble()
            SmartPlaylistRuleField.LATE_SKIPS -> row.lateSkips.toDouble()
            SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE ->
                if (row.audibleCompletions > 0) {
                    row.lastAudibleAtEpochMs?.let { ((now - it) / 86_400_000L).toDouble() }
                } else null
            SmartPlaylistRuleField.TITLE, SmartPlaylistRuleField.ARTIST_DISPLAY -> null
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
