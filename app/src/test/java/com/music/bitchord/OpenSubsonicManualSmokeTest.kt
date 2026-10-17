package com.music.bitchord

import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountDraft
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCredentialStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccess
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAssetStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeProbeResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeProbeTransport
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Optional real-server gates. Normal CI skips them because no endpoint/credentials
 * are supplied. A maintainer can opt in without committing any secrets.
 */
class OpenSubsonicManualSmokeTest {

    @Test
    fun configuredServerSupportsHealthCapabilitiesAndLibrarySnapshot() = runBlocking {
        val environment = realServerEnvironment() ?: return@runBlocking
        val provider = OpenSubsonicProvider(
            OpenSubsonicAccountConfig(
                accountId = "manual-smoke",
                serverUrl = environment.serverUrl,
                auth = environment.auth,
            ),
        )

        val health = provider.health()
        assertTrue("Configured OpenSubsonic server is unreachable", health.reachable)
        assertTrue(provider.capabilities().librarySync)
        assertTrue(provider.librarySnapshot().complete)
    }

    @Test
    fun configuredServerCanOpenRawDirectAudioFromProviderOwnedRuntimeAccess() = runBlocking {
        val environment = realServerEnvironment() ?: return@runBlocking
        val trackId = System.getenv("BITCHORD_OPENSUBSONIC_TRACK_ID")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@runBlocking

        val persistence = SmokeAccountPersistence()
        val credentials = SmokeCredentialStore()
        val accounts = OpenSubsonicAccountRepository(
            persistence = persistence,
            credentials = credentials,
            idFactory = { "manual-smoke" },
        )
        accounts.save(
            OpenSubsonicAccountDraft(
                serverUrl = environment.serverUrl,
                authMode = environment.authMode,
                username = environment.username.orEmpty(),
                secretReplacement = environment.secret,
            ),
        )
        val asset = MediaAssetEntity(
            id = "manual-smoke-asset",
            recordingId = "manual-smoke-recording",
            provider = "opensubsonic:manual-smoke",
            sourceKey = trackId,
            uri = "opensubsonic://asset/manual-smoke/runtime-smoke",
            path = null,
            dateAddedSeconds = null,
            dateModifiedSeconds = null,
            artworkUri = null,
        )
        val access = OpenSubsonicMediaAccess(
            assets = OpenSubsonicMediaAssetStore { requestedId ->
                asset.takeIf { it.id == requestedId }
            },
            accounts = accounts,
        )

        val ready = access.resolve(asset.id) as OpenSubsonicMediaAccessResult.Ready
        val parameters = ready.request.materializeParameters()
        assertEquals("raw", parameters["format"])
        assertEquals(trackId, parameters["id"])

        val result = ready.request.probe(
            OpenSubsonicRuntimeProbeTransport { endpointUrl, runtimeParameters ->
                executeOneByteRangeRequest(endpointUrl, runtimeParameters)
            },
        )
        assertEquals(OpenSubsonicRuntimeProbeResult.AVAILABLE, result)
    }

    private fun executeOneByteRangeRequest(
        endpointUrl: String,
        parameters: Map<String, String>,
    ): OpenSubsonicRuntimeProbeResult {
        val query = parameters.entries.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }
        val connection = URI("$endpointUrl?$query").toURL().openConnection() as HttpURLConnection
        return try {
            // Auth lives in the query. Never follow a redirect with these
            // parameters; a configured endpoint should be the final trusted host.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.requestMethod = "GET"
            val status = connection.responseCode
            when {
                status in 200..299 -> {
                    connection.inputStream.use { stream -> stream.read() }
                    OpenSubsonicRuntimeProbeResult.AVAILABLE
                }
                status == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    status == HttpURLConnection.HTTP_FORBIDDEN -> {
                    connection.errorStream?.close()
                    OpenSubsonicRuntimeProbeResult.AUTHENTICATION_REJECTED
                }
                else -> {
                    connection.errorStream?.close()
                    OpenSubsonicRuntimeProbeResult.REQUEST_REJECTED
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun realServerEnvironment(): RealServerEnvironment? {
        val serverUrl = System.getenv("BITCHORD_OPENSUBSONIC_URL")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val apiKey = System.getenv("BITCHORD_OPENSUBSONIC_API_KEY")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val username = System.getenv("BITCHORD_OPENSUBSONIC_USERNAME")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val password = System.getenv("BITCHORD_OPENSUBSONIC_PASSWORD")
            ?.takeIf(String::isNotEmpty)

        return when {
            apiKey != null -> RealServerEnvironment(
                serverUrl = serverUrl,
                auth = OpenSubsonicAuth.ApiKey(apiKey),
                authMode = OpenSubsonicAuthMode.API_KEY,
                username = null,
                secret = apiKey,
            )
            username != null && password != null -> RealServerEnvironment(
                serverUrl = serverUrl,
                auth = OpenSubsonicAuth.Password(username, password),
                authMode = OpenSubsonicAuthMode.PASSWORD,
                username = username,
                secret = password,
            )
            else -> error(
                "Set BITCHORD_OPENSUBSONIC_API_KEY or both " +
                    "BITCHORD_OPENSUBSONIC_USERNAME/BITCHORD_OPENSUBSONIC_PASSWORD",
            )
        }
    }

    private class RealServerEnvironment(
        val serverUrl: String,
        val auth: OpenSubsonicAuth,
        val authMode: OpenSubsonicAuthMode,
        val username: String?,
        val secret: String,
    ) {
        override fun toString(): String =
            "RealServerEnvironment(serverUrl=$serverUrl, auth=$auth, authMode=$authMode, username=$username, secret=<redacted>)"
    }

    private class SmokeAccountPersistence : OpenSubsonicAccountPersistence {
        private var record: OpenSubsonicAccountRecord? = null

        override fun read(): OpenSubsonicAccountRecord? = record

        override fun write(record: OpenSubsonicAccountRecord) {
            this.record = record
        }

        override fun clear() {
            record = null
        }
    }

    private class SmokeCredentialStore : OpenSubsonicCredentialStore {
        private val values = mutableMapOf<String, String>()

        override fun read(accountId: String): String? = values[accountId]

        override fun write(accountId: String, credential: String) {
            values[accountId] = credential
        }

        override fun clear(accountId: String) {
            values.remove(accountId)
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
