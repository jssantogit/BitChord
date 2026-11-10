package com.music.bitchord.ui.screens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.NativeMediaCardMetrics
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeMediaCardMetricsTest {
    @Test fun squareShelfAndRecentlyAddedUseOneEstablishedVisualWidthAndCaptionGap() {
        assertEquals(150.dp, NativeMediaCardMetrics.shelfWidth)
        assertEquals(NativeMediaCardMetrics.shelfWidth, SHELF_CARD_WIDTH)
        assertEquals(12.dp, NativeMediaCardMetrics.squareCoverRadius)
        assertEquals(RoundedCornerShape(12.dp), NativeMediaCardMetrics.squareCoverShape)
        assertEquals(10.dp, NativeMediaCardMetrics.shelfCaptionGap)
    }

    @Test fun discoveryTilesUseReferenceDeclaredRatioRadiusAndTitleInset() {
        assertEquals(1.777f, MOOD_CARD_ASPECT, 0.00001f)
        assertEquals(7.dp, NativeMediaCardMetrics.discoveryCornerRadius)
        assertEquals(RoundedCornerShape(7.dp), NativeMediaCardMetrics.discoveryShape)
        assertEquals(10.dp, NativeMediaCardMetrics.discoveryTitleInset)
    }
}
