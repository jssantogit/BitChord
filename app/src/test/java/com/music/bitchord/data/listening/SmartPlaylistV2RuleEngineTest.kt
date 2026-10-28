package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract first: AND/OR/NOT must never promote absent evidence into a match. */
class SmartPlaylistV2RuleEngineTest {
    private val now = 90L * 86_400_000L
    private val planner = SmartPlaylistV2RuleEngine(nowEpochMs = { now })

    private fun candidate(
        id: String,
        rating: Double? = null,
        completed: Long = 0,
        lastAudible: Long? = null,
        duration: Long? = 180_000L,
        available: Boolean = true,
    ) = RuleBasedRecordingCandidate(id, rating, completed, lastAudible, duration, available)

    private fun check(
        field: SmartPlaylistRuleField,
        operator: SmartPlaylistRuleOperator,
        value: Double? = null,
    ) = SmartPlaylistRule.Comparison(field, operator, value)

    @Test fun nestedAllAnySelectsExactlyTheMatchingRecording() {
        val query = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS, SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0),
                SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(
                    check(SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 4.0),
                    check(SmartPlaylistRuleField.DURATION_MS, SmartPlaylistRuleOperator.GREATER_THAN, 240_000.0),
                )),
            )),
        )
        val result = planner.preview(listOf(
            candidate("both", 5.0, completed = 1),
            candidate("long", 2.0, completed = 1, duration = 300_000L),
            candidate("short", 2.0, completed = 1),
            candidate("never", 5.0),
        ), query)
        assertEquals(listOf("both", "long"), result.map { it.recordingId })
    }

    @Test fun notUnknownMustNotMatchAndExplicitMissingCanBeQueried() {
        val notLow = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Not(check(
                SmartPlaylistRuleField.EXPLICIT_RATING,
                SmartPlaylistRuleOperator.LESS_THAN, 3.0,
            )),
        )
        assertEquals(listOf("good"), planner.preview(listOf(
            candidate("missing", rating = null),
            candidate("bad", rating = 2.0),
            candidate("good", rating = 5.0),
        ), notLow).map { it.recordingId })
        val missing = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.IS_MISSING),
        )
        assertEquals(listOf("missing"), planner.preview(
            listOf(candidate("missing"), candidate("rated", 1.0)), missing,
        ).map { it.recordingId })
    }

    @Test fun neverPlayedIsZeroCompletionsNotAncientLastPlay() {
        val never = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS, SmartPlaylistRuleOperator.EQUAL, 0.0),
        )
        assertEquals(listOf("never"), planner.preview(listOf(
            candidate("never", completed = 0),
            candidate("old", completed = 1, lastAudible = 1L),
        ), never).map { it.recordingId })
        val old = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 30.0),
        )
        assertEquals(listOf("old"), planner.preview(listOf(
            candidate("never", completed = 0),
            candidate("old", completed = 1, lastAudible = 1L),
        ), old).map { it.recordingId })
    }

    @Test fun identicalSourcesDeduplicateButConflictingSourcesAreRejected() {
        val query = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0),
        )
        val a = candidate("rec:a", rating = 2.0, completed = 1)
        assertEquals(listOf("rec:a"), planner.preview(listOf(
            a, a, candidate("rec:x", 5.0, completed = 1),
            candidate("rec:x", 4.0, completed = 1),
        ), query).map { it.recordingId })
    }

    @Test fun sortingAndLimitWorkBeyondOldOneHundredLimit() {
        val query = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0),
            sort = SmartPlaylistV2Sort.COMPLETIONS_ASC,
            limit = 150,
        )
        val tracks = (1..160).map { candidate("rec:%03d".format(it), completed = it.toLong()) }
        val result = planner.preview(tracks, query)
        assertEquals(150, result.size)
        assertEquals("rec:001", result.first().recordingId)
        assertEquals("rec:150", result.last().recordingId)
    }

    @Test fun moreThanFiveThousandCandidatesDoesNotBecomesFalseEmpty() {
        val tracks = (1..5_001).map { candidate("rec:%05d".format(it), completed = 1) }
        val query = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0), limit = 10,
        )
        assertEquals(10, planner.preview(tracks, query).size)
    }

    @Test fun invalidValueCombinationsAndEmptyGroupsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(rules = check(
                SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.GREATER_THAN,
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(rules = check(
                SmartPlaylistRuleField.AUDIBLE_COMPLETIONS, SmartPlaylistRuleOperator.IS_PRESENT, 3.0,
            ))
        }
        assertTrue(planner.preview(listOf(candidate("unavailable", 5.0, available = false)),
            SmartPlaylistV2Query(rules = check(
                SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.IS_PRESENT,
            ))).isEmpty())
    }
    @Test fun ambiguousRatingsCannotMasqueradeAsMissingOrPassNegation() {
        val unknown = candidate("conflict", rating = null).copy(ratingEvidenceUnknown = true)
        val definitelyMissing = candidate("missing", rating = null)
        val isMissing = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.IS_MISSING),
        )
        assertEquals(listOf("missing"), planner.preview(listOf(unknown, definitelyMissing), isMissing)
            .map { it.recordingId })
        val notOneStar = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Not(check(
                SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.EQUAL, 1.0,
            )),
        )
        assertTrue(planner.preview(listOf(unknown), notOneStar).isEmpty())
    }

    @Test fun titleAndArtistTextRulesAreCaseInsensitiveAndNested() {
        val matches = listOf(
            candidate("one").copy(title = "Super Shy", artistDisplay = "NewJeans"),
            candidate("two").copy(title = "Hype Boy", artistDisplay = "NEWJEANS"),
            candidate("three").copy(title = "Supernova", artistDisplay = "aespa"),
        )
        val query = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.ARTIST_DISPLAY, SmartPlaylistRuleOperator.EQUAL,
                    textValue = "newjeans",
                ),
                SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.CONTAINS,
                        textValue = "SHY",
                    ),
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.STARTS_WITH,
                        textValue = "hype",
                    ),
                )),
            )),
            sort = SmartPlaylistV2Sort.TITLE_ASC,
        )
        assertEquals(listOf("two", "one"), planner.preview(matches, query).map { it.recordingId })
    }

    @Test fun negativeTextComparisonMustNotIncludeBlankOrUnknownMetadata() {
        val query = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.DOES_NOT_CONTAIN,
                textValue = "live",
            ),
        )
        val input = listOf(
            candidate("unknown"),
            candidate("blank").copy(title = "  "),
            candidate("studio").copy(title = "Studio Mix"),
            candidate("live").copy(title = "Live Mix"),
        )
        assertEquals(listOf("studio"), planner.preview(input, query).map { it.recordingId })
        val negated = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.CONTAINS,
                textValue = "live",
            )),
        )
        assertEquals(listOf("studio"), planner.preview(input, negated).map { it.recordingId })
        val missing = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_MISSING,
            ),
            sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        )
        assertEquals(listOf("blank", "unknown"), planner.preview(input, missing).map { it.recordingId })
    }

    @Test fun metadataSortHasDeterministicTieBreakAndMissingValuesLast() {
        val rows = listOf(
            candidate("z").copy(title = "Alpha", artistDisplay = "Beta"),
            candidate("b").copy(title = "alpha", artistDisplay = "beta"),
            candidate("m").copy(title = "Zulu", artistDisplay = "Alpha"),
            candidate("a"),
        )
        val rule = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
            SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 0.0,
        )
        assertEquals(listOf("b", "z", "m", "a"), planner.preview(
            rows, SmartPlaylistV2Query(rule, sort = SmartPlaylistV2Sort.TITLE_ASC),
        ).map { it.recordingId })
        assertEquals(listOf("m", "b", "z", "a"), planner.preview(
            rows, SmartPlaylistV2Query(rule, sort = SmartPlaylistV2Sort.ARTIST_ASC),
        ).map { it.recordingId })
    }

    @Test fun invalidTextValueAndOperatorTypesAreRejectedBeforeEvaluation() {
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.GREATER_THAN,
                textValue = "Hello",
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.CONTAINS,
                textValue = "5",
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.CONTAINS,
                textValue = "   ",
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_MISSING,
                textValue = "x",
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.AUDIBLE_COMPLETIONS, SmartPlaylistRuleOperator.EQUAL,
                value = 2.0, textValue = "x",
            ))
        }
    }

    @Test fun earlyAndLateSkipsUseOnlyExplicitNavigationEvidence() {
        val rows = listOf(
            candidate("early").copy(earlySkips = 3, lateSkips = 0),
            candidate("late").copy(earlySkips = 0, lateSkips = 2),
            candidate("never").copy(earlySkips = 0, lateSkips = 0),
        )
        val early = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.EARLY_SKIPS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
                value = 2.0,
            ),
        )
        assertEquals(listOf("early"), planner.preview(rows, early).map { it.recordingId })
        val late = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.LATE_SKIPS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
                value = 1.0,
            ),
        )
        assertEquals(listOf("late"), planner.preview(rows, late).map { it.recordingId })
        val neverSkipped = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.EARLY_SKIPS, SmartPlaylistRuleOperator.EQUAL, 0.0,
                ),
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.LATE_SKIPS, SmartPlaylistRuleOperator.EQUAL, 0.0,
                ),
            )),
        )
        assertEquals(listOf("never"), planner.preview(rows, neverSkipped).map { it.recordingId })
    }

    @Test fun skipCountMustBeNonnegativeInteger() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.EARLY_SKIPS, SmartPlaylistRuleOperator.EQUAL,
                value = -1.0,
            ))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.LATE_SKIPS, SmartPlaylistRuleOperator.EQUAL,
                value = 0.5,
            ))
        }
    }

    @Test fun releaseTitleUsesCanonicalManyToManyMembershipWithoutChoosingAnArbitraryAlbum() {
        val candidates = listOf(
            candidate("newjeans").copy(releaseTitles = listOf("Get Up", "Super Shy")),
            candidate("other").copy(releaseTitles = listOf("OMG")),
            candidate("unlinked"),
        )
        val contains = SmartPlaylistV2Query(
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.RELEASE_TITLE,
                SmartPlaylistRuleOperator.CONTAINS, textValue = "get up"),
            sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        )
        assertEquals(listOf("newjeans"), planner.preview(candidates, contains).map { it.recordingId })
        val excludes = SmartPlaylistV2Query(
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.RELEASE_TITLE,
                SmartPlaylistRuleOperator.DOES_NOT_CONTAIN, textValue = "shy"),
            sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        )
        assertEquals(listOf("other"), planner.preview(candidates, excludes).map { it.recordingId })
        val missing = SmartPlaylistV2Query(
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.RELEASE_TITLE,
                SmartPlaylistRuleOperator.IS_MISSING),
        )
        assertEquals(listOf("unlinked"), planner.preview(candidates, missing).map { it.recordingId })
        val negated = SmartPlaylistV2Query(
            SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(SmartPlaylistRuleField.RELEASE_TITLE,
                SmartPlaylistRuleOperator.CONTAINS, textValue = "get")),
        )
        assertEquals(listOf("other"), planner.preview(candidates, negated).map { it.recordingId })
    }

    @Test fun fiftyThousandRecordingsRemainDeterministicAndDoNotSilentlyDisappear() {
        val candidates = (0 until 50_000).map { i ->
            candidate("rec:%05d".format(i), completed = 1)
                .copy(title = "Track %05d".format(i))
        }
        val query = SmartPlaylistV2Query(
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0),
            sort = SmartPlaylistV2Sort.TITLE_DESC,
            limit = 100,
        )
        val result = planner.preview(candidates, query).map { it.recordingId }
        assertEquals(100, result.size)
        assertEquals("rec:49999", result.first())
        assertEquals("rec:49900", result.last())
        assertEquals(result, planner.preview(candidates, query).map { it.recordingId })
    }

    @Test fun localRemoteSourceFilterRequiresActualActiveSource() {
        val local = candidate("local").copy(sourceKinds = setOf(SmartPlaylistSourceKind.LOCAL))
        val remote = candidate("remote").copy(sourceKinds = setOf(SmartPlaylistSourceKind.OPENSUBSONIC))
        val both = candidate("both").copy(sourceKinds = setOf(
            SmartPlaylistSourceKind.LOCAL, SmartPlaylistSourceKind.OPENSUBSONIC,
        ))
        val unknown = candidate("unknown") // Not a reliable claim of absence.
        val localOnly = SmartPlaylistV2Query(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.SOURCE_KIND, SmartPlaylistRuleOperator.EQUAL,
                textValue = "local",
            ), sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        )
        assertEquals(listOf("both", "local"), planner.preview(
            listOf(local, remote, both, unknown), localOnly,
        ).map { it.recordingId })

        val notRemote = SmartPlaylistV2Query(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.SOURCE_KIND, SmartPlaylistRuleOperator.NOT_EQUAL,
                textValue = "opensubsonic",
            ),
        )
        assertEquals(listOf("local"), planner.preview(
            listOf(local, remote, both, unknown), notRemote,
        ).map { it.recordingId })
        val negated = SmartPlaylistV2Query(
            SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.SOURCE_KIND, SmartPlaylistRuleOperator.EQUAL,
                textValue = "opensubsonic",
            )),
        )
        assertEquals(listOf("local"), planner.preview(
            listOf(local, remote, both, unknown), negated,
        ).map { it.recordingId })
    }

    @Test fun sourceFilterRejectsInvalidProviderOrTextOperators() {
        for (text in listOf("", "untrusted:some-account", "youtube")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.SOURCE_KIND,
                    SmartPlaylistRuleOperator.EQUAL, textValue = text,
                ))
            }
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.SOURCE_KIND,
                SmartPlaylistRuleOperator.CONTAINS, textValue = "local",
            ))
        }
    }

}
