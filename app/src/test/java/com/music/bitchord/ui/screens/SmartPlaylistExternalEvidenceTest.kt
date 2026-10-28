package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmartPlaylistExternalEvidenceTest {
    private fun record(scope: String = "scope-1") = OpenSubsonicAccountRecord(
        accountId = "account-1",
        serverUrl = "https://music.example",
        authMode = OpenSubsonicAuthMode.PASSWORD,
        username = "listener",
        ratingScopeId = scope,
    )

    private fun compare(field: SmartPlaylistRuleField) = SmartPlaylistRule.Comparison(
        field, SmartPlaylistRuleOperator.GREATER_OR_EQUAL, value = 1.0,
    )

    @Test fun accountScopeChangesRefreshButHealthAndSyncNoiseDoNot() = runTest {
        val account = MutableStateFlow<OpenSubsonicAccountRecord?>(record())
        val observed = mutableListOf<Unit>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            smartPlaylistAccountChanges(account).toList(observed)
        }
        assertTrue(observed.isEmpty()) // Ignore initial account snapshot.
        account.value = record().copy(lastHealthReachable = true, lastSyncSucceeded = true)
        assertEquals(0, observed.size)
        account.value = record(scope = "scope-2")
        assertEquals(1, observed.size)
        account.value = record(scope = "scope-2").copy(serverUrl = "https://new.example")
        assertEquals(2, observed.size)
        account.value = null // Forget is a real eligibility change.
        assertEquals(3, observed.size)
        collector.cancel()
    }

    @Test fun nestedAndNegatedRelativeDayRulesRequireActiveTimeTicks() {
        val nested = SmartPlaylistV2Query(
            SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(
                compare(SmartPlaylistRuleField.TITLE).copy(
                    field = SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                ),
                SmartPlaylistRule.Not(compare(SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE)),
            )),
        )
        assertTrue(nested.requiresRelativeAgeRefresh())
        assertFalse(SmartPlaylistV2Query(compare(
            SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
        )).requiresRelativeAgeRefresh())
    }

    @Test fun activeAgeFilterRefreshesPeriodicallyButOrdinaryFilterDoesNot() = runTest {
        val age = SmartPlaylistV2Query(compare(SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE))
        val count = mutableListOf<Unit>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            smartPlaylistRelativeAgeChanges(age).toList(count)
        }
        assertEquals(0, count.size)
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        assertEquals(1, count.size)
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        assertEquals(2, count.size)
        job.cancel()

        val plain = SmartPlaylistV2Query(compare(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS))
        val unchanged = mutableListOf<Unit>()
        val plainJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            smartPlaylistRelativeAgeChanges(plain).toList(unchanged)
        }
        advanceTimeBy(3 * 15 * 60_000L)
        runCurrent()
        assertTrue(unchanged.isEmpty())
        plainJob.cancel()
    }

    @Test fun nestedLocalRatingAgeTriggersForegroundOnlyRefresh() {
        val age = SmartPlaylistV2Query(
            SmartPlaylistRule.Not(compare(SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING)),
        )
        assertTrue(age.requiresRelativeAgeRefresh())
        assertFalse(SmartPlaylistV2Query(compare(
            SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
        )).requiresRelativeAgeRefresh())
    }



    @Test fun localFileAddedAgeAlsoActivatesForegroundOnlyClockRefresh() {
        val query = SmartPlaylistV2Query(
            SmartPlaylistRule.Not(compare(SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED)),
        )
        assertTrue(query.requiresRelativeAgeRefresh())
    }

}
