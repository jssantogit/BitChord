package com.music.bitchord

import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.ui.CatalogFilterCoverage
import com.music.bitchord.ui.catalogSearchFilterRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSearchFilterRouteTest {
    private val first = ProviderKey("catalog-one")
    private val second = ProviderKey("catalog-two")

    @Test fun changingFromArtistsToAlbumsRequestsTheUnqueriedFallback() {
        val route = catalogSearchFilterRoute(listOf(first, second), mapOf(
            first to CatalogFilterCoverage.EMPTY,
            second to CatalogFilterCoverage.NOT_QUERIED,
        ))
        assertEquals(second, route.provider)
        assertTrue(route.shouldFetch)
    }

    @Test fun returningToAnAlreadyFetchedTabRestoresPreferredProvider() {
        val route = catalogSearchFilterRoute(listOf(first, second), mapOf(
            first to CatalogFilterCoverage.MATCHING,
            second to CatalogFilterCoverage.MATCHING,
        ))
        assertEquals(first, route.provider)
        assertFalse(route.shouldFetch)
    }

    @Test fun pendingPrimaryMustNotExposeSecondaryResults() {
        val route = catalogSearchFilterRoute(listOf(first, second), mapOf(
            first to CatalogFilterCoverage.LOADING,
            second to CatalogFilterCoverage.MATCHING,
        ))
        assertEquals(first, route.provider)
        assertFalse(route.shouldFetch)
    }

    @Test fun noProviderIsQueriedWhenAllAreDisabledOrAlreadyEmpty() {
        assertNull(catalogSearchFilterRoute(emptyList(), mapOf(
            first to CatalogFilterCoverage.MATCHING,
        )).provider)
        assertNull(catalogSearchFilterRoute(listOf(first, second), mapOf(
            first to CatalogFilterCoverage.EMPTY,
            second to CatalogFilterCoverage.EMPTY,
        )).provider)
    }

    @Test fun newProviderInstancesRequireTheirOwnQuery() {
        val third = ProviderKey("catalog-one", "remote-instance")
        val route = catalogSearchFilterRoute(listOf(third, first), mapOf(
            first to CatalogFilterCoverage.MATCHING,
        ))
        assertEquals(third, route.provider)
        assertTrue(route.shouldFetch)
    }
}
