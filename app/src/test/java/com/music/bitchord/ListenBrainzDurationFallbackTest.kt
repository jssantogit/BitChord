package com.music.bitchord

import com.music.bitchord.data.scrobbling.listenBrainzDurationMsFromLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenBrainzDurationFallbackTest {
    private fun parse(value: String?): Long = listenBrainzDurationMsFromLabel(value)

    @Test fun acceptsMinuteAndHourDurationLabels() {
        assertEquals(0L, parse("00:00"))
        assertEquals(185_000L, parse("3:05"))
        assertEquals(3_723_000L, parse("1:02:03"))
        assertEquals(599_999_000L, parse("166:39:59"))
        assertEquals(3_723_000L, parse(" 1:02:03 "))
    }

    @Test fun rejectsMalformedNegativeOrOverflowDurations() {
        listOf(null, "", "3", "1:2:3:4", "-1:30", "1:-2",
            "1:60", "1:02:60", "1:60:00", "foo:12", "9223372036854775807:00",
            "999999999999999999:00:00", "1::03",
        ).forEach { value ->
            assertEquals("Unexpected parsed duration for $value", 0L, parse(value))
        }
    }
}
