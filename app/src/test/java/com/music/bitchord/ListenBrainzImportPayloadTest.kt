package com.music.bitchord

import com.music.bitchord.data.scrobbling.PendingListenBrainzListen
import com.music.bitchord.data.scrobbling.listenBrainzImportPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzImportPayloadTest {
    private fun listen(duration: Long = 120_000L) = PendingListenBrainzListen(
        occurrenceId = "repeat-1", accountKey = "sha256-of-account",
        startMs = 1_780_000_000_000L, endMs = 1_780_000_080_000L,
        listenedMs = 80_000L, durationMs = duration,
        artist = "aespa\n宁艺卓", title = "봄날 / 春 \"snow\" \t",
        album = "アルバム",
    )

    @Test fun queuedSubmissionUsesImportWithOriginalTimestampAndSafeUnicode() {
        val original = listen()
        val body = listenBrainzImportPayload(original)
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("import", root["listen_type"]?.jsonPrimitive?.content)
        val item = root["payload"]!!.jsonArray.single().jsonObject
        assertEquals(original.startMs / 1_000L, item["listened_at"]!!.jsonPrimitive.content.toLong())
        val track = item["track_metadata"]!!.jsonObject
        assertEquals(original.title, track["track_name"]!!.jsonPrimitive.content)
        assertEquals(original.artist, track["artist_name"]!!.jsonPrimitive.content)
        assertEquals(original.album, track["release_name"]!!.jsonPrimitive.content)
        assertEquals("BitChord", track["additional_info"]!!.jsonObject["submission_client"]!!.jsonPrimitive.content)
        assertFalse(body.contains("sha256-of-account"))
        assertFalse(body.contains("repeat-1"))
    }

    @Test fun unsupportedLongDurationIsOmittedInsteadOfRejected() {
        val longDuration = listen(duration = 2_073_600_001L)
        val root = Json.parseToJsonElement(listenBrainzImportPayload(longDuration)).jsonObject
        val info = root["payload"]!!.jsonArray[0].jsonObject["track_metadata"]!!
            .jsonObject["additional_info"]!!.jsonObject
        assertFalse(info.containsKey("duration_ms"))
        assertTrue(info.containsKey("start_ms"))
    }
}
