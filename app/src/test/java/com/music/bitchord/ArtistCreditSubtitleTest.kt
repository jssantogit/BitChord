package com.music.bitchord

import com.music.bitchord.data.catalog.artistCreditFromSubtitle
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistCreditSubtitleTest {
    @Test fun stripsContentTypeAndMetricsWithoutLosingArtist() {
        assertEquals("Chelsea Wolfe", artistCreditFromSubtitle("Song • Chelsea Wolfe"))
        assertEquals("21 Savage", artistCreditFromSubtitle("Album • 12.4M plays • 21 Savage"))
        assertEquals("aespa", artistCreditFromSubtitle("Single • 3:45 • aespa"))
    }

    @Test fun preservesUnrecognizedHistoricalSubtitle() {
        assertEquals("Song", artistCreditFromSubtitle("Song"))
        assertEquals("Local artist", artistCreditFromSubtitle("Local artist"))
    }
}
