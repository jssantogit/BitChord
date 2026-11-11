package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsMasterContractTest {
    @Test fun masterIsGroupContextAndNotAnEditionOrImageLicense() {
        val link = DiscogsLink(DiscogsKind.MASTER, 2024)
        val raw = """{"id":2024,"title":"Artist - Album",
            "artists":[{"name":"Artist"}],"year":1999,
            "main_release":42,"genres":["Electronic"],"styles":["House"],
            "images":[{"uri":"https://restricted.example/master.jpg"}]}"""
        val master = DiscogsRecordParser.parseMaster(link, raw)
        assertNotNull(master)
        assertEquals("Artist - Album", master!!.title)
        assertEquals(42L, master.mainReleaseId)
        assertEquals(listOf("Electronic"), master.genres)
        assertEquals(listOf("House"), master.styles)
        assertFalse(master.toString().contains("restricted.example"))
        assertFalse(master.canSupplyArtwork())
        assertFalse(master.canSupplyAudio())
        assertNull(DiscogsRecordParser.parseMaster(DiscogsLink(DiscogsKind.RELEASE, 2024), raw))
        assertNull(DiscogsRecordParser.parseMaster(link, """{"id":2222,"title":"Other"}"""))
    }

    @Test fun masterLookupOnlyUsesReadOnlyEndpointAndNeverInventsEditionIdentity() = runBlocking {
        val paths = mutableListOf<String>()
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                paths += path
                return DiscogsHttpResponse(200,
                    """{"id":2024,"title":"Artist - Album","main_release":42,
                    "artists":[{"name":"Artist"}]}""")
            }
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L))
        val result = client.fetchMaster(2024)
        assertTrue(result is DiscogsLookupResult.FoundMaster)
        assertEquals(42L, (result as DiscogsLookupResult.FoundMaster).record.mainReleaseId)
        assertEquals(listOf("masters/2024"), paths)
    }
}
