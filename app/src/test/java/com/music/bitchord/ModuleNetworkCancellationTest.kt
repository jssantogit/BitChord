package com.music.bitchord

import com.music.bitchord.data.sources.module.awaitModuleHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleNetworkCancellationTest {
    @Test fun stoppingModuleExecutionCancelsRealQuickJsFetchSocket() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeadersDelay(30, TimeUnit.SECONDS)
                .setBody("late JS fetch"))
            server.start()
            val call = OkHttpClient().newCall(
                Request.Builder().url(server.url("/module-fetch")).build())
            val pending = async(Dispatchers.IO) { awaitModuleHttp(call) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            pending.cancel()
            withTimeout(5_000) { pending.join() }
            assertTrue(call.isCanceled())
        }
    }
}
