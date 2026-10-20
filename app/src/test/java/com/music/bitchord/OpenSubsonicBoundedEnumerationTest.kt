package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicEnumerationMetrics
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenSubsonicBoundedEnumerationTest {
    private val config = OpenSubsonicAccountConfig(
        "home", "https://music.example.test", OpenSubsonicAuth.ApiKey("private-secret"),
    )
    private class Fixture(
        private val reply: suspend (String, Map<String, String>) -> String,
    ) : OpenSubsonicTransport {
        override suspend fun get(endpoint: String, parameters: Map<String, String>) =
            reply(endpoint, parameters)
    }

    private fun list(ids: List<String>): String =
        """{"subsonic-response":{"status":"ok","albumList2":{"album":[${ids.joinToString(",") { id -> """{"id":"$id","name":"Album"}""" }}]}}}"""

    private fun album(id: String): String = """
        {"subsonic-response":{"status":"ok","album":{
            "id":"$id","songCount":1,"song":[
                {"id":"song-$id","title":"Track $id","artist":"Artist"}
            ]
        }}}
    """.trimIndent()

    @Test fun boundedConcurrentEnumerationKeepsDeterministicProviderOrderAndReportsCosts() = runBlocking {
        val ids = (1..17).map { "album-%02d".format(it) }
        val active = AtomicInteger()
        val observedPeak = AtomicInteger()
        var metrics: OpenSubsonicEnumerationMetrics? = null
        val provider = OpenSubsonicProvider(
            config = config,
            pageSize = 20,
            transport = Fixture { endpoint, parameters ->
                when (endpoint) {
                    "getAlbumList2" -> {
                        assertEquals("0", parameters["offset"])
                        list(ids)
                    }
                    "getAlbum" -> {
                        val inflight = active.incrementAndGet()
                        observedPeak.accumulateAndGet(inflight, ::maxOf)
                        try {
                            delay(20)
                            album(parameters.getValue("id"))
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                    else -> error("Unexpected endpoint")
                }
            },
            maxConcurrentAlbumFetches = 4,
            onEnumerationMetrics = { metrics = it },
        )
        val first = provider.librarySnapshot()
        val second = provider.librarySnapshot()
        assertTrue(first.complete)
        assertEquals(ids.map { "song-$it" }, first.tracks.map { it.externalTrackId })
        assertEquals(first, second)
        assertTrue("Expected real overlapping album reads", observedPeak.get() in 2..4)
        assertEquals(0, active.get())
        val report = requireNotNull(metrics)
        assertEquals(1, report.pageRequests)
        assertEquals(17, report.albumRequests)
        assertEquals(17, report.trackCount)
        assertTrue(report.peakAlbumRequestsInFlight in 2..4)
        assertTrue(report.elapsedMillis >= 0)
        assertTrue(report.toString().contains("17"))
        assertTrue(!report.toString().contains("private-secret"))
    }

    @Test fun failedParallelAlbumFetchNeverYieldsPartialCompleteSnapshotOrMetrics() = runBlocking {
        var metrics: OpenSubsonicEnumerationMetrics? = null
        val provider = OpenSubsonicProvider(
            config = config,
            pageSize = 10,
            transport = Fixture { endpoint, parameters ->
                when (endpoint) {
                    "getAlbumList2" -> list((1..8).map { "album-$it" })
                    "getAlbum" -> {
                        if (parameters["id"] == "album-3") throw IOException("429: retry later")
                        delay(20)
                        album(parameters.getValue("id"))
                    }
                    else -> error("unexpected")
                }
            },
            maxConcurrentAlbumFetches = 3,
            onEnumerationMetrics = { metrics = it },
        )
        try {
            provider.librarySnapshot()
            fail("Incomplete enumeration unexpectedly returned a successful snapshot")
        } catch (_: IOException) { }
        assertEquals(null, metrics)
    }

    @Test fun cancellationIsNotHiddenByConcurrentEnumeration() = runBlocking {
        val provider = OpenSubsonicProvider(
            config = config,
            pageSize = 10,
            transport = Fixture { endpoint, parameters ->
                if (endpoint == "getAlbumList2") list(listOf("a", "b", "c"))
                else if (parameters["id"] == "b") throw CancellationException("cancel")
                else { delay(30); album(parameters.getValue("id")) }
            },
            maxConcurrentAlbumFetches = 3,
        )
        try {
            provider.librarySnapshot()
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun concurrencyConfigurationMustBeBounded() {
        for (invalid in listOf(0, 9)) {
            try {
                OpenSubsonicProvider(config = config, maxConcurrentAlbumFetches = invalid)
                fail("Expected invalid concurrency to be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
