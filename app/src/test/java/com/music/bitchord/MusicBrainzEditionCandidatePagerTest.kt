package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MusicBrainzEditionCandidatePagerTest {
    private fun hit(index: Int, relevant: Boolean = false) = MusicBrainzReleaseHit(
        musicBrainzId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
        title = if (relevant) "SUPER REAL ME" else "Other edition $index",
        country = "KR", releaseDate = "2024-03-25",
        artistDisplay = "ILLIT", status = "Official",
        releaseGroupMbid = "529f87a1-1081-48a4-8ee1-3c08de229858",
        releaseGroupPrimaryType = "EP",
    ).also { it.artistCreditMbids = listOf("11111111-1111-4111-8111-111111111111") }

    private fun response(
        rows: List<MusicBrainzReleaseHit>, total: Int, offset: Int,
        consumed: Int = rows.size,
    ) = MusicBrainzCatalogSearchResult.Success(
        MusicBrainzCatalogPage(rows, total, offset, consumed),
    )

    @Test fun followsEveryRawOffsetAndFindsLateRelevantEditionWithoutFabricatingPagination() =
        runBlocking {
            val calls = mutableListOf<Pair<Int, Int>>()
            val rows = (0 until 29).map { hit(it, it in setOf(0, 16, 28)) }
            val chosen = collectCompleteMusicBrainzEditionCandidates(
                maxRelevant = 4,
                isRelevant = { it.title == "SUPER REAL ME" },
                searchPage = { limit, offset ->
                    calls += limit to offset
                    response(rows.drop(offset).take(limit), rows.size, offset)
                },
            )
            assertEquals(listOf(12 to 0, 12 to 12, 12 to 24), calls)
            assertEquals(listOf(0, 16, 28).map { rows[it].musicBrainzId },
                chosen?.map { it.musicBrainzId })
        }

    @Test fun exactlyFortyEightSourceRowsAreCoveredWithinFourPageBudget() =
        runBlocking {
            val calls = mutableListOf<Int>()
            val rows = (0 until 48).map { hit(it, it == 47) }
            val chosen = collectCompleteMusicBrainzEditionCandidates(
                maxRelevant = 4, isRelevant = { it.title == "SUPER REAL ME" },
                searchPage = { limit, offset ->
                    calls += offset
                    response(rows.drop(offset).take(limit), rows.size, offset)
                },
            )
            assertEquals(listOf(0, 12, 24, 36), calls)
            assertEquals(listOf(rows.last().musicBrainzId),
                chosen?.map { it.musicBrainzId })
        }

    @Test fun overBudgetTotalFailsAfterFirstPageWithoutClaimingFullCoverage() =
        runBlocking {
            var calls = 0
            val chosen = collectCompleteMusicBrainzEditionCandidates(
                maxRelevant = 4, isRelevant = { true },
                searchPage = { limit, offset ->
                    calls++
                    response((0 until limit).map(::hit), 49, offset)
                },
            )
            assertNull(chosen)
            assertEquals(1, calls)
        }

    @Test fun rawRowsConsumedCannotDisagreeWithDecodedRowsOrChangeTotals() =
        runBlocking {
            suspend fun check(fault: Int): List<MusicBrainzReleaseHit>? {
                val first = (0 until 12).map(::hit)
                val second = (12 until 14).map(::hit)
                return collectCompleteMusicBrainzEditionCandidates(
                    maxRelevant = 4,
                    isRelevant = { it.title == "SUPER REAL ME" },
                    searchPage = { _, offset ->
                        if (offset == 0) response(first, 14, 0)
                        else when (fault) {
                            0 -> response(second, 14, 13)
                            1 -> response(second, 15, 12)
                            2 -> response(second, 14, 12, consumed = 3)
                            3 -> response(listOf(first[0], second[1]), 14, 12)
                            else -> response(emptyList(), 14, 12, consumed = 0)
                        }
                    },
                )
            }
            (0..4).forEach { assertNull("fault $it", check(it)) }
        }

    @Test fun incompleteNonterminalRawPageStopsInsteadOfSkippingPositions() =
        runBlocking {
            var calls = 0
            val matches = collectCompleteMusicBrainzEditionCandidates(
                maxRelevant = 4, isRelevant = { it.title == "SUPER REAL ME" },
                searchPage = { _, offset ->
                    calls++
                    response(
                        (0 until 3).map { hit(it, it == 0) },
                        total = 15, offset = offset,
                    )
                },
            )
            assertNull(matches)
            assertEquals(1, calls)
        }

    @Test fun tooManyRelevantCandidatesAndUnavailableLaterPageFailClosed() =
        runBlocking {
            var calls = 0
            val tooMany = collectCompleteMusicBrainzEditionCandidates(
                maxRelevant = 4, isRelevant = { true },
                searchPage = { limit, offset ->
                    calls++
                    response((0 until limit).map(::hit), 24, offset)
                },
            )
            assertNull(tooMany)
            assertEquals(1, calls)
            val unavailable = collectCompleteMusicBrainzEditionCandidates(
                maxRelevant = 4, isRelevant = { it.title == "SUPER REAL ME" },
                searchPage = { _, offset ->
                    if (offset == 0) response(
                        (0 until 12).map { hit(it, it == 0) }, 14, 0,
                    ) else MusicBrainzCatalogSearchResult.Failure(
                        MusicBrainzCatalogSearchFailure.UNAVAILABLE,
                    )
                },
            )
            assertNull(unavailable)
        }
}
