package com.music.bitchord.acquisition.a0
import org.junit.Assert.*
import org.junit.Test
class A0SearchTicketRegistryTest {
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
