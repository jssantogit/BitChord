package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A contextual Songs filter must never require invented Rating/Listening evidence. */
class ContextualSmartFilterTest {
    private fun track(id: String, title: String = "Song $id", durationMs: Long? = 180_000) =
        CanonicalBrowseTrack(id, title, "NewJeans", durationMs, null,
            listOf(CanonicalBrowseMediaAsset("asset:$id", "opensubsonic:current")))

    private fun filter(rule: SmartPlaylistRule) =
        SavedSmartPlaylistV2Filter("f:one", "Example", rule)

    @Test fun onlyCanonicalRecordingFieldsAreAllowedForBrowseWithoutExtraQueries() {
        val text = SmartPlaylistRule.Comparison(SmartPlaylistRuleField.TITLE,
            SmartPlaylistRuleOperator.CONTAINS, textValue = "get up")
        val duration = SmartPlaylistRule.Comparison(SmartPlaylistRuleField.DURATION_MS,
            SmartPlaylistRuleOperator.GREATER_OR_EQUAL, value = 100_000.0)
        val nested = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL,
            listOf(text, SmartPlaylistRule.Not(duration)))
        assertTrue(filter(nested).isSupportedForCanonicalSongBrowse())
        assertFalse(filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.EXPLICIT_RATING,
            SmartPlaylistRuleOperator.IS_MISSING,
        )).isSupportedForCanonicalSongBrowse())
        assertFalse(filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_TITLE,
            SmartPlaylistRuleOperator.IS_PRESENT,
        )).isSupportedForCanonicalSongBrowse())
        assertFalse(filter(SmartPlaylistRule.Reference("another-playlist"))
            .isSupportedForCanonicalSongBrowse())
    }

    @Test fun filtersProviderScopedSongsWithoutReorderingOrFetchingFromOtherAccounts() {
        val rows = listOf(track("first", "Get Up"), track("second", "Ditto"),
            track("third", "Get Up (Live)", 20_000))
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.CONTAINS, textValue = "GET UP"),
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.DURATION_MS,
                SmartPlaylistRuleOperator.GREATER_THAN, value = 100_000.0),
        ))
        assertEquals(listOf("first"),
            filterCanonicalBrowseSongs(rows, filter(rule), "opensubsonic:current").map { it.recordingId })
        assertEquals(listOf("first", "third"),
            filterCanonicalBrowseSongs(rows, filter(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.CONTAINS,
                textValue = "get up",
            )), "opensubsonic:current").map { it.recordingId })
    }

    @Test fun noImplicitPlaylistLimitOrFallbackToUnfilteredResults() {
        val rows = (1..10_101).map { track("rec:$it") }
        val rule = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.DURATION_MS,
            SmartPlaylistRuleOperator.IS_PRESENT,
        )
        assertEquals(10_101, filterCanonicalBrowseSongs(rows, filter(rule), "opensubsonic:current").size)
        val forbidden = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.EXPLICIT_RATING,
            SmartPlaylistRuleOperator.IS_MISSING,
        ))
        assertThrows(IllegalArgumentException::class.java) {
            filterCanonicalBrowseSongs(rows, forbidden, "opensubsonic:current")
        }
        val unknownDuration = listOf(track("missing", durationMs = null))
        assertEquals(0, filterCanonicalBrowseSongs(unknownDuration,
            filter(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DURATION_MS,
                SmartPlaylistRuleOperator.GREATER_THAN, value = 20_000.0,
            )), "opensubsonic:current").size)
    }
    @Test fun contextualSongFilteringRejectsMixedOrCrossAccountPlaybackAssets() {
        val rule = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
        ))
        val valid = track("valid")
        val fromAnotherAccount = valid.copy(
            recordingId = "other",
            availableAssets = listOf(CanonicalBrowseMediaAsset(
                "asset:other", "opensubsonic:different",
            )),
        )
        assertThrows(IllegalArgumentException::class.java) {
            filterCanonicalBrowseSongs(listOf(valid, fromAnotherAccount), rule,
                "opensubsonic:current")
        }
        assertThrows(IllegalArgumentException::class.java) {
            filterCanonicalBrowseSongs(listOf(valid), rule, "opensubsonic:different")
        }
        assertThrows(IllegalArgumentException::class.java) {
            filterCanonicalBrowseSongs(listOf(valid), rule, "")
        }
    }

    @Test fun knownSourceKindRulesWorkForActualScopedLocalAndRemoteAssets() {
        val local = com.music.bitchord.data.catalog.CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
        val localTrack = track("same").copy(
            availableAssets = listOf(CanonicalBrowseMediaAsset("media:1", local)),
        )
        val remoteTrack = track("same")
        val onlyLocal = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.SOURCE_KIND, SmartPlaylistRuleOperator.EQUAL,
            textValue = "local",
        ))
        val onlyRemote = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.SOURCE_KIND, SmartPlaylistRuleOperator.EQUAL,
            textValue = "opensubsonic",
        ))
        assertTrue(onlyLocal.isSupportedForCanonicalSongBrowse())
        assertEquals(listOf("same"),
            filterCanonicalBrowseSongs(listOf(localTrack), onlyLocal, local)
                .map { it.recordingId })
        assertTrue(filterCanonicalBrowseSongs(listOf(localTrack), onlyRemote, local).isEmpty())
        assertEquals(listOf("same"),
            filterCanonicalBrowseSongs(listOf(remoteTrack), onlyRemote, "opensubsonic:current")
                .map { it.recordingId })
        assertTrue(filterCanonicalBrowseSongs(listOf(remoteTrack), onlyLocal,
            "opensubsonic:current").isEmpty())
        val notRemote = filter(SmartPlaylistRule.Not(onlyRemote.rules))
        assertEquals(listOf("same"),
            filterCanonicalBrowseSongs(listOf(localTrack), notRemote, local)
                .map { it.recordingId })
        assertTrue(filterCanonicalBrowseSongs(listOf(remoteTrack), notRemote,
            "opensubsonic:current").isEmpty())
    }

    @Test fun unknownSourceDoesNotBecomeLocalUnderNotOrNegativeOperator() {
        val unknownProvider = "custom-unverified:session"
        val input = listOf(track("unproven").copy(
            availableAssets = listOf(CanonicalBrowseMediaAsset("unproven", unknownProvider)),
        ))
        val remote = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.SOURCE_KIND, SmartPlaylistRuleOperator.EQUAL,
            textValue = "opensubsonic",
        )
        val filters = listOf(remote,
            SmartPlaylistRule.Not(remote),
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.SOURCE_KIND,
                SmartPlaylistRuleOperator.NOT_EQUAL, textValue = "opensubsonic"))
        for (rule in filters) {
            val f = filter(rule)
            assertTrue(f.isSupportedForCanonicalSongBrowse())
            assertTrue(filterCanonicalBrowseSongs(input, f, unknownProvider).isEmpty())
        }
    }

    @Test fun fiftyThousandSongSourceFilterPreservesAllMatchesAndOriginalOrder() {
        val rows = (1..50_123).map { index ->
            track("rec:%05d".format(index), if (index % 7 == 0) "Selected" else "Other")
        }
        val rule = filter(SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.EQUAL, textValue = "selected"),
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.SOURCE_KIND,
                SmartPlaylistRuleOperator.EQUAL, textValue = "opensubsonic"),
        )))
        val matches = filterCanonicalBrowseSongs(rows, rule, "opensubsonic:current")
        val expected = rows.filter { it.title == "Selected" }
        assertEquals(expected.size, matches.size)
        assertEquals(expected.map { it.recordingId }, matches.map { it.recordingId })
        assertEquals(expected.map { it.availableAssets }, matches.map { it.availableAssets })
    }

    @Test fun mixedKnownAndUnknownLocalAssetDatesDoNotProveNegativeComparisons() {
        val local = com.music.bitchord.data.catalog.CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
        val song = track("shared").copy(availableAssets = listOf(
            CanonicalBrowseMediaAsset("local:one", local),
            CanonicalBrowseMediaAsset("local:two", local),
        ))
        val nowSeconds = System.currentTimeMillis() / 1_000
        val partialDates = mapOf("shared" to listOf(nowSeconds - 20L * 86_400L, null))
        val present = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
            SmartPlaylistRuleOperator.IS_PRESENT,
        ))
        val old = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
            SmartPlaylistRuleOperator.GREATER_THAN, value = 10.0,
        ))
        val unequal = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
            SmartPlaylistRuleOperator.NOT_EQUAL, value = 5.0,
        ))
        assertEquals(listOf("shared"),
            filterCanonicalBrowseSongs(listOf(song), present, local, partialDates)
                .map { it.recordingId })
        assertEquals(listOf("shared"),
            filterCanonicalBrowseSongs(listOf(song), old, local, partialDates)
                .map { it.recordingId })
        assertTrue(filterCanonicalBrowseSongs(listOf(song), unequal, local,
            partialDates).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            filterCanonicalBrowseSongs(listOf(song), present, local)
        }
    }

    @Test fun zeroMediaStoreAddedSecondsAreUnknownLikeGlobalBatchProjection() {
        val local = com.music.bitchord.data.catalog.CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
        val song = track("zero").copy(
            availableAssets = listOf(CanonicalBrowseMediaAsset("local:zero", local)),
        )
        val present = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
            SmartPlaylistRuleOperator.IS_PRESENT,
        ))
        val unknown = mapOf("zero" to listOf<Long?>(0L))
        assertTrue(filterCanonicalBrowseSongs(listOf(song), present, local, unknown).isEmpty())
        assertTrue(filterCanonicalBrowseSongs(listOf(song),
            filter(SmartPlaylistRule.Not(present.rules)), local, unknown).isEmpty())
    }

}
