package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteArrivalMotionTest {
    @Test fun tabsOnlyAnimateBetweenTwoRootTabsRatherThanThroughDetails() {
        assertTrue(shouldAnimateRouteArrival("tab:0", "tab:2", reduced = false, rootTabPrefix = "tab:"))
        assertFalse(shouldAnimateRouteArrival("tab:0", "tab:0", reduced = false, rootTabPrefix = "tab:"))
        assertFalse(shouldAnimateRouteArrival("tab:2", "settings", reduced = false, rootTabPrefix = "tab:"))
        assertFalse(shouldAnimateRouteArrival("settings", "tab:2", reduced = false, rootTabPrefix = "tab:"))
        assertFalse(shouldAnimateRouteArrival("tab:0", "tab:2", reduced = true, rootTabPrefix = "tab:"))
    }

    @Test fun settingsAndPlaylistFormTransitionsAcceptOnlyRealPageChanges() {
        assertTrue(shouldAnimateRouteArrival("settings:home", "settings:appearance", reduced = false))
        assertTrue(shouldAnimateRouteArrival("settings:appearance", "settings:home", reduced = false))
        assertTrue(shouldAnimateRouteArrival("picker", "create", reduced = false))
        assertTrue(shouldAnimateRouteArrival("wizard:IDENTITY", "wizard:SONGS", reduced = false))
        assertTrue(shouldAnimateRouteArrival("wizard:REVIEW", "wizard:SONGS", reduced = false))
        assertTrue(shouldAnimateRouteArrival("library:ALBUMS:shelf", "library:ALBUMS:album-id", reduced = false))
        assertTrue(shouldAnimateRouteArrival("library:ALBUMS:album-id", "library:ALBUMS:shelf", reduced = false))
        assertFalse(shouldAnimateRouteArrival("library:ARTISTS:shelf", "library:ARTISTS:shelf", reduced = false))
        assertFalse(shouldAnimateRouteArrival("create", "create", reduced = false))
        assertFalse(shouldAnimateRouteArrival("picker", "create", reduced = true))
    }

    @Test fun frostedFilterMenuOpensFromCornerWithoutChangingMeasuredSurface() {
        val first = frostedMenuEntranceFrame(progress = 0f, reducedMotion = false)
        assertEquals(0.3f, first.alpha, 0.0001f)
        assertEquals(0.94f, first.scale, 0.0001f)
        val mid = frostedMenuEntranceFrame(progress = 0.5f, reducedMotion = false)
        assertEquals(0.65f, mid.alpha, 0.0001f)
        assertEquals(0.97f, mid.scale, 0.0001f)
        val settled = frostedMenuEntranceFrame(progress = 1f, reducedMotion = false)
        assertEquals(1f, settled.alpha, 0.0001f)
        assertEquals(1f, settled.scale, 0.0001f)
        assertEquals(settled, frostedMenuEntranceFrame(0f, reducedMotion = true))
        assertEquals(settled, frostedMenuEntranceFrame(Float.NaN, reducedMotion = false))
    }

    @Test fun internalLibraryWizardAndSettingsUseTheSameStationaryTabPolicy() {
        for (prefix in listOf("settings:", "wizard:", "library:", "playlist-manager:")) {
            assertTrue(shouldAnimateRouteArrival(prefix + "first", prefix + "second", false, prefix))
            assertFalse(shouldAnimateRouteArrival(prefix + "first", "other:second", false, prefix))
        }
    }

    @Test fun rootTabsKeepTheirContentStationaryInEitherDirection() {
        val initial = rootTabArrivalFrame(0f)
        assertEquals(0f, initial.translationDp, 0.0001f)
        assertEquals(0.975f, initial.alpha, 0.0001f)
        val middle = rootTabArrivalFrame(0.5f)
        assertEquals(0f, middle.translationDp, 0.0001f)
        assertTrue(middle.alpha > initial.alpha)
        val settled = rootTabArrivalFrame(1f)
        assertEquals(1f, settled.alpha, 0.0001f)
        assertEquals(0f, settled.translationDp, 0.0001f)
        assertEquals(settled, rootTabArrivalFrame(Float.NaN))
        assertEquals(settled, rootTabArrivalFrame(Float.POSITIVE_INFINITY))
    }

    @Test fun rootTabContentFadesUseAppleTracedAnimatorTimingWithoutSizeSpring() {
        assertEquals(150, rootTabFadeMillis(reducedMotion = false))
        assertEquals(0, rootTabFadeMillis(reducedMotion = true))
        val transform = rootTabContentTransform(reducedMotion = false)
        org.junit.Assert.assertNull(transform.sizeTransform)
        assertTrue(transform.targetContentEnter != androidx.compose.animation.EnterTransition.None)
        assertTrue(transform.initialContentExit != androidx.compose.animation.ExitTransition.None)
    }

    @Test fun reducedMotionReplacesRootTabsWithoutAContentFade() {
        val transform = rootTabContentTransform(reducedMotion = true)
        org.junit.Assert.assertNull(transform.sizeTransform)
        assertEquals(
            androidx.compose.animation.EnterTransition.None,
            transform.targetContentEnter,
        )
        assertEquals(
            androidx.compose.animation.ExitTransition.None,
            transform.initialContentExit,
        )
    }

    @Test fun incomingPageUsesBoundedSubtleOffsetWithoutBlackCrossfade() {
        val entry = routeArrivalFrame(progress = 0f, forward = true)
        assertEquals(0.96f, entry.alpha, 0.0001f)
        assertEquals(12f, entry.translationDp, 0.0001f)
        val back = routeArrivalFrame(progress = 0f, forward = false)
        assertEquals(-12f, back.translationDp, 0.0001f)
        val halfway = routeArrivalFrame(progress = 0.5f, forward = true)
        assertEquals(0.98f, halfway.alpha, 0.0001f)
        assertEquals(6f, halfway.translationDp, 0.0001f)
        val settled = routeArrivalFrame(progress = 1f, forward = true)
        assertEquals(1f, settled.alpha, 0.0001f)
        assertEquals(0f, settled.translationDp, 0.0001f)
        assertEquals(settled, routeArrivalFrame(Float.NaN, forward = false))
    }
    @Test fun pushedRoutesNeverCrossfadeTheRetiringPage() {
        for (route in listOf(
            "unified_library", "canonical_playlist_library", "settings",
            "account_scrobbling", "replay", "history", "local_recent_additions",
            "artist:123", "public_playlist:123", "rediscovery:123",
            "smart_playlist_builder",
        )) {
            assertTrue("Opening $route", mainPageRequiresImmediateExit("tab:3", route, "tab:"))
            assertTrue("Returning from $route", mainPageRequiresImmediateExit(route, "tab:3", "tab:"))
        }
    }

    @Test fun openingSearchFromAnyRootTabNeverCompositesThePreviousTab() {
        val search = "tab:3"
        for (source in listOf("tab:0", "tab:1", "tab:2")) {
            assertTrue(mainPageMustReplaceImmediately(source, search, "tab:", search))
            assertTrue(mainPageMustReplaceImmediately(search, source, "tab:", search))
        }
        assertFalse(mainPageMustReplaceImmediately("tab:0", "tab:2", "tab:", search))
    }

    @Test fun libraryToPlaylistBuilderAndItsBackNavigationNeverDoubleDraw() {
        for (library in listOf("tab:2", "canonical_playlist_library")) {
            assertTrue(mainPageMustReplaceImmediately(
                library, "smart_playlist_builder", "tab:", "tab:3",
            ))
            assertTrue(mainPageMustReplaceImmediately(
                "smart_playlist_builder", library, "tab:", "tab:3",
            ))
        }
        assertFalse(mainPageMustReplaceImmediately("tab:2", "settings", "tab:", "tab:3"))
    }

    @Test fun atomicPageChangeHasNoEntryFadeOrRetainedExitFade() {
        val transform = immediatePageContentTransform()
        assertEquals(
            androidx.compose.animation.EnterTransition.None,
            transform.targetContentEnter,
        )
        org.junit.Assert.assertNull(transform.sizeTransform)
        assertEquals(
            androidx.compose.animation.ExitTransition.None,
            immediatePageContentTransform(reducedMotion = true).initialContentExit,
        )
    }

    @Test fun rootPeersAloneRetainTheirApprovedCrossfade() {
        assertFalse(mainPageRequiresImmediateExit("tab:0", "tab:3", "tab:"))
        assertFalse(mainPageRequiresImmediateExit("tab:3", "tab:1", "tab:"))
        assertTrue(mainPageRequiresImmediateExit("settings", "history", "tab:"))
        assertTrue(mainPageRequiresImmediateExit("artist:one", "album:two", "tab:"))
    }

    @Test fun isolatedPageTransformNeverFadesTheOutgoingRoute() {
        val transform = isolatedPageContentTransform(reducedMotion = false)
        org.junit.Assert.assertNotEquals(
            androidx.compose.animation.ExitTransition.None,
            transform.initialContentExit,
        )
        org.junit.Assert.assertNull(transform.sizeTransform)
        assertEquals(
            androidx.compose.animation.EnterTransition.None,
            isolatedPageContentTransform(reducedMotion = true).targetContentEnter,
        )
    }

}
