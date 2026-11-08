package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicTabRoutingTest {
    @Test fun existingRoutesKeepTheirIndicesBehindTheFiveVisualTabs() {
        assertEquals(0, appleMusicVisualIndexForRoute(0)) // Início
        assertEquals(1, appleMusicVisualIndexForRoute(1)) // Explorar
        assertEquals(3, appleMusicVisualIndexForRoute(2)) // Biblioteca
        assertEquals(4, appleMusicVisualIndexForRoute(3)) // Buscar
    }

    @Test fun futureRadioTabDoesNotDispatchNavigation() {
        assertNull(appleMusicRouteForVisualIndex(2))
    }

    @Test fun liveTabsMapBackToTheirOriginalRoutes() {
        assertEquals(0, appleMusicRouteForVisualIndex(0))
        assertEquals(1, appleMusicRouteForVisualIndex(1))
        assertEquals(2, appleMusicRouteForVisualIndex(3))
        assertEquals(3, appleMusicRouteForVisualIndex(4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRouteIndexCannotBeDisplayed() {
        appleMusicVisualIndexForRoute(4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonexistentVisualIndexCannotNavigate() {
        appleMusicRouteForVisualIndex(5)
    }
}
