package com.music.bitchord

import com.music.bitchord.playback.HistoricalMediaAssetUnavailable
import com.music.bitchord.playback.historicalMediaUnavailableCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class HistoricalMediaAssetUnavailableTest {
    @Test fun historicalMediaFailureRemainsPermanentThroughMedia3ExceptionWrappers() {
        val archival = HistoricalMediaAssetUnavailable("No authorized audio asset")
        val wrapped = IOException("Media3 load failure", IllegalStateException("loader", archival))
        assertSame(archival, historicalMediaUnavailableCause(wrapped))
        assertEquals("No authorized audio asset", historicalMediaUnavailableCause(wrapped)?.message)
    }

    @Test fun temporaryProviderFailuresAreNotMisclassifiedAsArchival() {
        assertNull(historicalMediaUnavailableCause(IOException("Temporary network timeout")))
        assertNull(historicalMediaUnavailableCause(null))
    }

    @Test fun arbitraryDeepExceptionsDoNotUnwrapWithoutBound() {
        val archival = HistoricalMediaAssetUnavailable("No authorized audio asset")
        var current: Throwable = archival
        repeat(9) { current = IOException("wrapper", current) }
        assertNull(historicalMediaUnavailableCause(current))
    }
}
