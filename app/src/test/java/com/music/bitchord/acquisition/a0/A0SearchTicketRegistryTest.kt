package com.music.bitchord.acquisition.a0
import org.junit.Assert.*
import org.junit.Test
class A0SearchTicketRegistryTest {
    @Test fun correlateOnlyValidatedResponsesToTheirQueryOrdinal() {
        val tickets=A0SearchTicketRegistry(81)
        assertTrue(tickets.register(82))
        tickets.creditValidated(81)
        tickets.creditValidated(82)
        tickets.creditValidated(82)
        tickets.creditValidated(999999)
        assertEquals(listOf(1,2,0),tickets.responseCounts())
        assertTrue(tickets.register(83))
        tickets.creditValidated(83)
        assertEquals(listOf(1,2,1),tickets.responseCounts())
        assertFalse(tickets.register(84))
        assertFalse(tickets.toString().contains("999999"))
    }
    @Test fun liveTicketSetAcceptsNewFallbackWithoutAcceptingUnknownTokens() {
        val tickets=A0SearchTicketRegistry(27182)
        val accepted=tickets.accepted
        assertEquals(setOf(27182),accepted.toSet())
        assertTrue(tickets.register(27183))
        assertTrue(27183 in accepted)
        assertFalse(27184 in accepted)
        tickets.creditValidated(27183)
        tickets.creditValidated(27184)
        assertEquals(listOf(0,1,0),tickets.responseCounts())
        assertTrue(tickets.register(27185))
        tickets.creditValidated(27185)
        assertEquals(listOf(0,1,1),tickets.responseCounts())
        assertFalse(tickets.register(27186))
    }

    @Test fun onlyOriginalAndTwoRegisteredVariantsArePermitted() {
        val tickets=A0SearchTicketRegistry(81)
        assertFalse(tickets.register(81))
        assertFalse(tickets.register(0))
        assertTrue(tickets.register(82))
        assertTrue(tickets.register(83))
        assertFalse(tickets.register(84))
        assertEquals(setOf(81,82,83),tickets.accepted.toSet())
    }
}
