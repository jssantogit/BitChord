package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Sorting one newcomer should not re-rank 384 old Soulseek offers each time. */
class A0StableCandidateInsertionTest {
    data class Offer(val score:Int,val peer:Int,val sequence:Int)

    @Test fun incrementalOrderMatchesStableFullSortAcrossThousandIncomingOffers() {
        val incremental=mutableListOf<Offer>()
        val baseline=mutableListOf<Offer>()
        val ranking=compareByDescending<Offer> { it.score }
            .thenBy { it.peer }
        repeat(1024) { index ->
            val candidate=Offer((index*31+7)%89,index%13,index)
            A0StableCandidateInsertion.add(incremental,candidate,ranking)
            baseline.add(candidate)
            baseline.sortWith(ranking)
            if(baseline.size>384)baseline.removeAt(baseline.lastIndex)
            if(incremental.size>384)incremental.removeAt(incremental.lastIndex)
            assertEquals("Offer "+index,baseline,incremental)
        }
        assertEquals(384,incremental.size)
    }

    @Test fun stableInsertionRetainsOriginalArrivalOrderForComparatorTies() {
        val ranking=compareBy<Offer> { it.score }
        val result=mutableListOf<Offer>()
        repeat(30) { index ->
            A0StableCandidateInsertion.add(result,Offer(10,index,index),ranking)
        }
        assertEquals((0 until 30).toList(),result.map { it.sequence })
    }

    @Test fun binaryInsertionUsesLogarithmicComparisonsEvenAtMaxCacheCapacity() {
        val result=(0..383).map { Offer(it,it,it) }.toMutableList()
        var comparisons=0
        val comparator=Comparator<Offer> { a,b ->
            comparisons++
            a.score.compareTo(b.score)
        }
        A0StableCandidateInsertion.add(result,Offer(200,999,999),comparator)
        assertTrue("Expected binary insertion, was "+comparisons,
            comparisons<=10)
        assertEquals(385,result.size)
        assertEquals(999,result[201].peer)
    }
}
