package com.music.bitchord

import com.music.bitchord.data.scrobbling.listenBrainzEscapedJsonText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ListenBrainzJsonEscapeTest {
    @Test fun controlCharactersAndMultilingualMetadataRoundTripWithoutInvalidJson() {
        val value = "BTS \"가사\"\\歌詞\t한글\r\n日本語\u0000中文"
        val encoded = listenBrainzEscapedJsonText(value)
        val document = """{"track_name":"$encoded"}"""
        val decoded = Json.parseToJsonElement(document).jsonObject.getValue("track_name")
            .jsonPrimitive.content
        assertEquals(value, decoded)
        assertFalse(document.contains('\u0000'))
        assertFalse(document.contains('\t'))
        assertFalse(document.contains('\r'))
    }

    @Test fun quotesAndBackslashesNeverBreakListenBrainzPayload() {
        val value = "Artist \"Name\" \\ Track"
        val encoded = listenBrainzEscapedJsonText(value)
        val roundTrip = Json.parseToJsonElement("""{"artist_name":"$encoded"}""")
            .jsonObject.getValue("artist_name").jsonPrimitive.content
        assertEquals(value, roundTrip)
    }
}
