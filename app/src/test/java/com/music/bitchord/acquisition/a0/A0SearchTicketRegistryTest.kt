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
