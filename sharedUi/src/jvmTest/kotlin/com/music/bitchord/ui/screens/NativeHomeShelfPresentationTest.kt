package com.music.bitchord.ui.screens

import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.HERO_CARD_RATIO
import com.music.bitchord.ui.components.NativeMediaCardMetrics
import com.music.bitchord.ui.components.heroCardWidth
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeHomeShelfPresentationTest {
    @Test fun recentCoversAreSquareWithCaptionsOutsideEvenWhenFirstOrAfterEditorial() {
        assertEquals(HomeShelfPresentation.RECENT_COVERS, homeShelfPresentation("Recents", 0, true))
        assertEquals(HomeShelfPresentation.RECENT_COVERS, homeShelfPresentation("Recents", 2, true))
        assertEquals(HomeShelfPresentation.RECENT_COVERS, homeShelfPresentation(" recents ", 1, false))
        assertEquals(1f, NativeMediaCardMetrics.recentCoverRatio, 0.00001f)
        assertEquals(NativeMediaCardMetrics.shelfWidth, NativeMediaCardMetrics.recentCoverWidth)
    }

    @Test fun onlyTheFirstNonRecentShelfCanBeEditorialHero() {
        assertEquals(HomeShelfPresentation.EDITORIAL_HERO, homeShelfPresentation("Lançamentos", 0, true))
        assertEquals(HomeShelfPresentation.SQUARE_SHELF, homeShelfPresentation("Lançamentos", 0, false))
        assertEquals(HomeShelfPresentation.SQUARE_SHELF, homeShelfPresentation("Escolha a dedo", 1, true))
    }

    @Test fun editorialHeroIsTallAndLeavesTheNextCardVisible() {
        assertEquals(0.75f, HERO_CARD_RATIO, 0.00001f)
        assertEquals(223.2f, heroCardWidth(360.dp).value, 0.01f)
        assertEquals(320.dp, heroCardWidth(700.dp))
    }
}
