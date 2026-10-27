package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

class SoulseekBatchPayloadTest {
    @Test fun preservesIndividualHintsInOrderedIntentFields() {
        val entries=SoulseekBatchPayload.decode(
            listOf("ILLIT - My World","ILLIT - Magnetic"),
            listOf("SUPER REAL ME","SUPER REAL ME"),
            intArrayOf(155,161),
        )!!
        assertEquals(2,entries.size)
        assertEquals("ILLIT - Magnetic",entries[1].query)
        assertEquals("SUPER REAL ME",entries[1].album)
        assertEquals(161,entries[1].durationSeconds)
    }
    @Test fun rejectsMalformedOrOversizedExternalizedBatchEntirely() {
        assertNull(SoulseekBatchPayload.decode(
            listOf("A - One","B - Two"), listOf("Album"), intArrayOf(120,120)))
        assertNull(SoulseekBatchPayload.decode(
            listOf("A - One","B - Two"), listOf("Album","Album"), intArrayOf(120)))
        assertNull(SoulseekBatchPayload.decode(
            (1..65).map {"Track $it"}, (1..65).map {"Album"},
            IntArray(65){100}))
        assertNull(SoulseekBatchPayload.decode(
            listOf("A - One","B - \u0000"), listOf("Album","Album"), intArrayOf(120,120)))
        assertNull(SoulseekBatchPayload.decode(
            listOf("A - One","B - Two"), listOf("Album", "A".repeat(121)),
            intArrayOf(120,120)))
    }
}
