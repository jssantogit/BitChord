package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Optional real-server gate. Normal CI skips it because no endpoint/credentials
 * are supplied. A maintainer can opt in without committing any secrets.
 */
class OpenSubsonicManualSmokeTest {

    @Test
    fun configuredServerSupportsHealthCapabilitiesAndLibrarySnapshot() = runBlocking {
        val serverUrl = System.getenv("BITCHORD_OPENSUBSONIC_URL")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@runBlocking

        val apiKey = System.getenv("BITCHORD_OPENSUBSONIC_API_KEY")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val username = System.getenv("BITCHORD_OPENSUBSONIC_USERNAME")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val password = System.getenv("BITCHORD_OPENSUBSONIC_PASSWORD")
            ?.takeIf(String::isNotEmpty)

        val auth = when {
            apiKey != null -> OpenSubsonicAuth.ApiKey(apiKey)
            username != null && password != null -> OpenSubsonicAuth.Password(username, password)
            else -> error(
                "Set BITCHORD_OPENSUBSONIC_API_KEY or both " +
                    "BITCHORD_OPENSUBSONIC_USERNAME/BITCHORD_OPENSUBSONIC_PASSWORD",
            )
        }
        val provider = OpenSubsonicProvider(
            OpenSubsonicAccountConfig(
                accountId = "manual-smoke",
                serverUrl = serverUrl,
                auth = auth,
            ),
        )

        val health = provider.health()
        assertTrue("Configured OpenSubsonic server is unreachable", health.reachable)
        assertTrue(provider.capabilities().librarySync)
        assertTrue(provider.librarySnapshot().complete)
    }
}
