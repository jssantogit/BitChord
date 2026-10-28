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

    @Test fun interleavedDuplicatesPreserveUniqueRecordingIdsAndRejectEveryConflictingSnapshot() {
        val good = candidate("rec:good", rating = 2.0, completed = 1)
        val first = candidate("rec:conflict", rating = 5.0, completed = 1)
        val conflicting = first.copy(explicitRating = 4.0)
        val query = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0),
            sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        )
        val result = planner.preview(
            listOf(first, good, good, conflicting, first, good),
            query,
        )
        assertEquals(listOf("rec:good"), result.map { it.recordingId })
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
        assertTrue(planner.preview(input, missing).isEmpty())
    }

    @Test fun unresolvedCanonicalTitleOrArtistDoesNotProveMetadataMissing() {
        val inputs = listOf(
            candidate("unknown").copy(title = null, artistDisplay = null),
            candidate("blank").copy(title = " ", artistDisplay = " "),
            candidate("known").copy(title = "Ditto", artistDisplay = "NewJeans"),
        )
        for (field in listOf(SmartPlaylistRuleField.TITLE, SmartPlaylistRuleField.ARTIST_DISPLAY)) {
            val present = SmartPlaylistRule.Comparison(field, SmartPlaylistRuleOperator.IS_PRESENT)
            val missing = SmartPlaylistRule.Comparison(field, SmartPlaylistRuleOperator.IS_MISSING)
            for (rule in listOf(missing, SmartPlaylistRule.Not(present))) {
                assertTrue(planner.preview(inputs, SmartPlaylistV2Query(
                    rule, sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
                )).isEmpty())
            }
            assertEquals(listOf("known"), planner.preview(inputs, SmartPlaylistV2Query(
                present, sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
            )).map { it.recordingId })
        }
    }

    @Test fun incompleteReleaseTitleCannotSatisfyNegativeOrNegatedFilter() {
        val partial = candidate("partial").copy(releaseTitles = listOf("Studio EP", " "))
        val complete = candidate("complete").copy(releaseTitles = listOf("Studio EP"))
        val positive = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_TITLE,
            SmartPlaylistRuleOperator.CONTAINS, textValue = "live",
        )
        val negative = positive.copy(operator = SmartPlaylistRuleOperator.DOES_NOT_CONTAIN)
        val missing = positive.copy(operator = SmartPlaylistRuleOperator.IS_MISSING,
            textValue = null)
        val inputs = listOf(partial, complete)
        for (rule in listOf(negative, SmartPlaylistRule.Not(positive))) {
            assertEquals(listOf("complete"), planner.preview(inputs, SmartPlaylistV2Query(
                rule, sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
            )).map { it.recordingId })
        }
        assertTrue(planner.preview(listOf(partial), SmartPlaylistV2Query(missing)).isEmpty())
        assertEquals(listOf("partial"), planner.preview(listOf(partial), SmartPlaylistV2Query(
            positive.copy(textValue = "studio"),
        )).map { it.recordingId })
        assertEquals(listOf("partial"), planner.preview(listOf(partial), SmartPlaylistV2Query(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.RELEASE_TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
            ),
        )).map { it.recordingId })
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

    @Test fun yearComparisonUsesOnlyKnownLinkedMusicBrainzReleaseYears() {
        val fullyKnown = candidate("known").copy(
            releaseYears = listOf(2020, 2026), releaseYearEvidenceUnknown = false,
        )
        val partlyUnknown = candidate("partial").copy(
            releaseYears = listOf(2026), releaseYearEvidenceUnknown = true,
        )
        val notEnriched = candidate("unverified")
        val equal2026 = SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_YEAR, SmartPlaylistRuleOperator.EQUAL, 2026.0,
        ), sort = SmartPlaylistV2Sort.RECORDING_ID_ASC)
        assertEquals(listOf("known", "partial"), planner.preview(
            listOf(fullyKnown, partlyUnknown, notEnriched), equal2026,
        ).map { it.recordingId })
        val not2020 = SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_YEAR, SmartPlaylistRuleOperator.NOT_EQUAL, 2020.0,
        ))
        // A known matching release invalidates NOT_EQUAL; unknown linked
        // releases cannot be silently counted as "not released in 2020".
        assertTrue(planner.preview(listOf(fullyKnown, partlyUnknown, notEnriched), not2020).isEmpty())
        val only2026 = fullyKnown.copy(recordingId = "only-2026", releaseYears = listOf(2026))
        assertEquals(listOf("only-2026"), planner.preview(
            listOf(only2026), not2020,
        ).map { it.recordingId })
        val notUnderUnknown = SmartPlaylistV2Query(SmartPlaylistRule.Not(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.RELEASE_YEAR, SmartPlaylistRuleOperator.EQUAL, 2020.0,
            ),
        ))
        assertTrue(planner.preview(listOf(partlyUnknown, notEnriched), notUnderUnknown).isEmpty())
    }

    @Test fun yearFilterRejectsFractionalOrOutOfRangeYearsAndMissingClaim() {
        for (bad in listOf(-1.0, 999.0, 2024.5, 10_000.0)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.RELEASE_YEAR, SmartPlaylistRuleOperator.EQUAL, bad,
                ))
            }
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.RELEASE_YEAR, SmartPlaylistRuleOperator.IS_MISSING,
            ))
        }
    }

    @Test fun linkedAlbumArtistsUseAnyForPositiveAndAllForNegative() {
        val shared = candidate("shared").copy(
            albumArtistNames = listOf("NewJeans", "Various Artists"),
            albumArtistEvidenceUnknown = false,
        )
        val partial = candidate("partial").copy(
            albumArtistNames = listOf("NewJeans"),
            albumArtistEvidenceUnknown = true,
        )
        val missing = candidate("missing")
        fun rule(op: SmartPlaylistRuleOperator) = SmartPlaylistV2Query(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.ALBUM_ARTIST, op,
                textValue = "newjeans",
            ),
            sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        )
        assertEquals(listOf("partial", "shared"), planner.preview(
            listOf(shared, partial, missing), rule(SmartPlaylistRuleOperator.EQUAL),
        ).map { it.recordingId })
        assertTrue(planner.preview(
            listOf(shared, partial, missing), rule(SmartPlaylistRuleOperator.NOT_EQUAL),
        ).isEmpty())
        val other = candidate("other").copy(
            albumArtistNames = listOf("Various Artists"),
            albumArtistEvidenceUnknown = false,
        )
        assertEquals(listOf("other"), planner.preview(
            listOf(other, shared, partial, missing),
            rule(SmartPlaylistRuleOperator.NOT_EQUAL),
        ).map { it.recordingId })
        assertTrue(planner.preview(
            listOf(missing), SmartPlaylistV2Query(
                SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.ALBUM_ARTIST,
                    SmartPlaylistRuleOperator.EQUAL, textValue = "NewJeans",
                )),
            ),
        ).isEmpty())
    }

    @Test fun unverifiedAlbumArtistMissingCannotPretendThereIsNoArtist() {
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.ALBUM_ARTIST,
                SmartPlaylistRuleOperator.IS_MISSING,
            ))
        }
    }


    @Test fun trackNumberMatchesAnyCanonicalEditionButNegativesRequireCompleteEvidence() {
        val full = candidate("full").copy(
            trackNumbers = listOf(1, 5), trackNumberEvidenceUnknown = false,
        )
        val partial = candidate("partial").copy(
            trackNumbers = listOf(5), trackNumberEvidenceUnknown = true,
        )
        val other = candidate("other").copy(
            trackNumbers = listOf(7), trackNumberEvidenceUnknown = false,
        )
        val missing = candidate("missing")
        fun query(op: SmartPlaylistRuleOperator, value: Double? = 1.0) =
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TRACK_NUMBER, op, value,
            ), sort = SmartPlaylistV2Sort.RECORDING_ID_ASC)
        val rows = listOf(full, partial, other, missing)
        assertEquals(listOf("full"), planner.preview(
            rows, query(SmartPlaylistRuleOperator.EQUAL),
        ).map { it.recordingId })
        assertEquals(listOf("other"), planner.preview(
            rows, query(SmartPlaylistRuleOperator.NOT_EQUAL),
        ).map { it.recordingId })
        assertEquals(listOf("full", "partial"), planner.preview(
            rows, query(SmartPlaylistRuleOperator.EQUAL, 5.0),
        ).map { it.recordingId })
        assertEquals(listOf("full", "other", "partial"), planner.preview(
            rows, query(SmartPlaylistRuleOperator.IS_PRESENT, null),
        ).map { it.recordingId })
        assertTrue(planner.preview(listOf(partial, missing), SmartPlaylistV2Query(
            SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TRACK_NUMBER, SmartPlaylistRuleOperator.EQUAL, 1.0,
            )),
        )).isEmpty())
    }

    @Test fun trackNumberRejectsUnknownAsMissingAndNonIntegerTargets() {
        for (bad in listOf(-1.0, 0.0, 1.5, 100_000.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.TRACK_NUMBER, SmartPlaylistRuleOperator.EQUAL, bad,
                ))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TRACK_NUMBER, SmartPlaylistRuleOperator.IS_MISSING,
            ))
        }
    }


    @Test fun creditedTrackArtistsAreDistinctFromDisplayStringAndProtectUnknownUnderNot() {
        val credited = candidate("credited").copy(
            artistDisplay = "Guest with Lead",
            trackArtistNames = listOf("Lead", "Guest"),
            trackArtistEvidenceUnknown = false,
        )
        val partial = candidate("partial").copy(
            artistDisplay = "Lead",
            trackArtistNames = listOf("Lead"),
            trackArtistEvidenceUnknown = true,
        )
        val other = candidate("other").copy(
            artistDisplay = "Lead",
            trackArtistNames = listOf("Other"),
            trackArtistEvidenceUnknown = false,
        )
        val noCredits = candidate("no-credits").copy(artistDisplay = "Lead")
        val rows = listOf(credited, partial, other, noCredits)
        fun q(op: SmartPlaylistRuleOperator, txt: String? = "lead") =
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TRACK_ARTIST, op, textValue = txt,
            ), sort = SmartPlaylistV2Sort.RECORDING_ID_ASC)
        assertEquals(listOf("credited", "partial"), planner.preview(
            rows, q(SmartPlaylistRuleOperator.EQUAL),
        ).map { it.recordingId })
        assertEquals(listOf("other"), planner.preview(
            rows, q(SmartPlaylistRuleOperator.NOT_EQUAL),
        ).map { it.recordingId })
        assertEquals(listOf("credited", "partial"), planner.preview(
            rows, q(SmartPlaylistRuleOperator.CONTAINS, "lea"),
        ).map { it.recordingId })
        assertTrue(planner.preview(listOf(partial, noCredits), SmartPlaylistV2Query(
            SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TRACK_ARTIST,
                SmartPlaylistRuleOperator.EQUAL, textValue = "guest",
            )),
        )).isEmpty())
        assertEquals(listOf("credited", "other", "partial"), planner.preview(
            rows, q(SmartPlaylistRuleOperator.IS_PRESENT, null),
        ).map { it.recordingId })
        assertThrows(IllegalArgumentException::class.java) {
            q(SmartPlaylistRuleOperator.IS_MISSING, null)
        }
    }


    @Test fun nestedRuleProjectionIncludesOnlyFieldsNeededByEvaluation() {
        val tree = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DURATION_MS, SmartPlaylistRuleOperator.GREATER_THAN, 120_000.0,
            ),
            SmartPlaylistRule.Not(SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.TRACK_NUMBER, SmartPlaylistRuleOperator.EQUAL, 1.0,
                ),
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.TRACK_ARTIST, SmartPlaylistRuleOperator.CONTAINS,
                    textValue = "lead",
                ),
            ))),
        ))
        assertEquals(setOf(
            SmartPlaylistRuleField.DURATION_MS,
            SmartPlaylistRuleField.TRACK_NUMBER,
            SmartPlaylistRuleField.TRACK_ARTIST,
        ), tree.referencedFields())
    }



    @Test fun localRatingAgeUsesOnlyKnownLocalTimestampAndNotRemotePreference() {
        val day = 86_400_000L
        val engine = SmartPlaylistV2RuleEngine(nowEpochMs = { 10L * day })
        val local = candidate("local").copy(
            localRatingUpdatedAtEpochMs = 7L * day, localRatingDateEvidenceKnown = true,
        )
        val absent = candidate("absent").copy(localRatingDateEvidenceKnown = true)
        val unknown = candidate("unknown")
        val future = candidate("future").copy(
            localRatingUpdatedAtEpochMs = 11L * day, localRatingDateEvidenceKnown = true,
        )
        val rows = listOf(local, absent, unknown, future)
        fun query(op: SmartPlaylistRuleOperator, value: Double? = 3.0) =
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING, op, value,
            ), sort = SmartPlaylistV2Sort.RECORDING_ID_ASC)
        assertEquals(listOf("local"), engine.preview(
            rows, query(SmartPlaylistRuleOperator.GREATER_OR_EQUAL),
        ).map { it.recordingId })
        assertEquals(listOf("absent"), engine.preview(
            rows, query(SmartPlaylistRuleOperator.IS_MISSING, null),
        ).map { it.recordingId })
        assertTrue(engine.preview(listOf(unknown, future), SmartPlaylistV2Query(
            SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 3.0,
            )),
        )).isEmpty())
        for (bad in listOf(-1.0, 1.5, 100_001.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                query(SmartPlaylistRuleOperator.EQUAL, bad)
            }
        }
    }


    @Test fun canonicalReleaseArtworkReferenceIsPositiveEvidenceNotMissingProof() {
        val artwork = candidate("artwork").copy(hasReleaseArtworkReference = true)
        val unknown = candidate("unknown")
        val predicate = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE,
            SmartPlaylistRuleOperator.IS_PRESENT,
        )
        assertEquals(listOf("artwork"), planner.preview(
            listOf(artwork, unknown), SmartPlaylistV2Query(
                predicate, sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
            ),
        ).map { it.recordingId })
        assertTrue(planner.preview(
            listOf(unknown), SmartPlaylistV2Query(SmartPlaylistRule.Not(predicate)),
        ).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE,
                SmartPlaylistRuleOperator.IS_MISSING,
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE,
                SmartPlaylistRuleOperator.EQUAL, 1.0,
            ))
        }
    }



    @Test fun localRatingSortIsDeterministicAndMissingTimestampsStayLast() {
        val newest = candidate("newest").copy(
            localRatingUpdatedAtEpochMs = now - 1000L, localRatingDateEvidenceKnown = true,
        )
        val oldA = candidate("old-a").copy(
            localRatingUpdatedAtEpochMs = now - 2000L, localRatingDateEvidenceKnown = true,
        )
        val oldB = candidate("old-b").copy(
            localRatingUpdatedAtEpochMs = now - 2000L, localRatingDateEvidenceKnown = true,
        )
        val missing = candidate("missing").copy(localRatingDateEvidenceKnown = true)
        val future = candidate("future").copy(
            localRatingUpdatedAtEpochMs = now + 1L, localRatingDateEvidenceKnown = true,
        )
        val unknown = candidate("unknown")
        val rows = listOf(future, missing, oldB, newest, unknown, oldA)
        val rule = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.DURATION_MS, SmartPlaylistRuleOperator.IS_PRESENT,
        )
        assertEquals(listOf("newest", "old-a", "old-b", "future", "missing", "unknown"),
            planner.preview(rows, SmartPlaylistV2Query(
                rule, sort = SmartPlaylistV2Sort.LOCAL_RATING_NEWEST,
            )).map { it.recordingId })
        assertEquals(listOf("old-a", "old-b", "newest", "future", "missing", "unknown"),
            planner.preview(rows, SmartPlaylistV2Query(
                rule, sort = SmartPlaylistV2Sort.LOCAL_RATING_OLDEST,
            )).map { it.recordingId })
    }



    @Test fun localAddedDatePredicateRequiresActiveLocalSourceAndCompleteNegativeEvidence() {
        val day = 86_400_000L
        val engine = SmartPlaylistV2RuleEngine(nowEpochMs = { 10L * day })
        val local = candidate("local").copy(
            sourceKinds = setOf(SmartPlaylistSourceKind.LOCAL),
            localAddedEpochMs = listOf(5L * day, 9L * day),
            localAddedDateEvidenceUnknown = false,
        )
        val partial = candidate("partial").copy(
            sourceKinds = setOf(SmartPlaylistSourceKind.LOCAL),
            localAddedEpochMs = listOf(5L * day),
            localAddedDateEvidenceUnknown = true,
        )
        val remote = candidate("remote").copy(
            sourceKinds = setOf(SmartPlaylistSourceKind.OPENSUBSONIC),
            localAddedEpochMs = listOf(5L * day),
            localAddedDateEvidenceUnknown = false,
        )
        val unknown = candidate("unknown")
        fun query(op: SmartPlaylistRuleOperator, value: Double? = 5.0) =
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
                op, value,
            ), sort = SmartPlaylistV2Sort.RECORDING_ID_ASC)
        val rows = listOf(local, partial, remote, unknown)
        assertEquals(listOf("local", "partial"), engine.preview(
            rows, query(SmartPlaylistRuleOperator.EQUAL),
        ).map { it.recordingId })
        assertEquals(listOf("local", "partial"), engine.preview(
            rows, query(SmartPlaylistRuleOperator.IS_PRESENT, null),
        ).map { it.recordingId })
        assertTrue(engine.preview(
            rows, query(SmartPlaylistRuleOperator.NOT_EQUAL),
        ).isEmpty())
        assertTrue(engine.preview(listOf(partial, unknown, remote), SmartPlaylistV2Query(
            SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
                SmartPlaylistRuleOperator.EQUAL, 3.0,
            )),
        )).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            query(SmartPlaylistRuleOperator.IS_MISSING, null)
        }
        for (bad in listOf(-1.0, 1.5, 100_001.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                query(SmartPlaylistRuleOperator.EQUAL, bad)
            }
        }
    }

}
