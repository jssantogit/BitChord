package com.music.bitchord

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.sources.ModuleSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSourcePolicyTest {
    @Test fun revocationCancelsCustomModuleIndexAndLeavesDownloadCapabilityEnabled() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeadersDelay(30, TimeUnit.SECONDS)
                .setBody("{}"))
            server.start()
            val config = SourceConfig(
                id = "module-one", kind = SourceKind.CUSTOM_MODULE,
                baseUrl = server.url("/index.json").toString(), enabled = true,
            )
            val key = ProviderKey("custom_module", config.id)
            fun policy(playback: Boolean) = ProviderCapabilityPolicy(
                descriptors = listOf(ProviderDescriptor(
                    key, setOf(ProviderCapability.EXTENSION_PLAYBACK, ProviderCapability.DOWNLOAD),
                    ProviderReadiness.READY,
                )),
                choices = mapOf(
                    ProviderCapability.EXTENSION_PLAYBACK to listOf(ProviderChoice(key, playback, 0)),
                    ProviderCapability.DOWNLOAD to listOf(ProviderChoice(key, true, 0)),
                ),
            )
            val gate = ProviderPolicyGate(policy(true))
            val source = ModuleSource(config, ProviderCapability.EXTENSION_PLAYBACK) { gate }
            val pending = async(Dispatchers.IO) { source.search("test", limit = 5) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            gate.replace(policy(false))
            assertTrue(withTimeout(5_000) { pending.await() }.isEmpty())
            assertTrue(gate.open(ProviderCapability.DOWNLOAD, key) != null)
        }
    }
}
