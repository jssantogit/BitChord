package com.music.bitchord

import androidx.media3.datasource.DataSource
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessFailure
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaDataSourceFactory
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeAudioRequest
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeTransport
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenSubsonicMediaTransportTest {

    @Test
    fun `runtime request is not created until provider transport is resolved`() = runTest {
        var calls = 0
        val runtime = runtimeRequest()
        val transport = OpenSubsonicRuntimeTransport {
            calls += 1
            OpenSubsonicMediaAccessResult.Ready(runtime)
        }

        assertEquals(0, calls)
        val resolved = transport.resolveRuntime(
            recordingId = "recording-1",
            mediaAssetId = "asset-1",
            providerKey = "opensubsonic:home",
        )

        assertEquals(1, calls)
        assertSame(runtime, resolved)
    }

    @Test
    fun `creating Media3 data source does not resolve provider access early`() {
        var calls = 0
        val transport = OpenSubsonicRuntimeTransport {
            calls += 1
            OpenSubsonicMediaAccessResult.Failure(
                OpenSubsonicMediaAccessFailure.MEDIA_ASSET_NOT_FOUND,
            )
        }
        val factory = OpenSubsonicMediaDataSourceFactory(
            transport = transport,
            upstreamFactory = DataSource.Factory {
                error("HTTP upstream must not be created before DataSource open")
            },
        )

        factory.create(
            recordingId = "recording-1",
            mediaAssetId = "asset-1",
            providerKey = "opensubsonic:home",
        )

        assertEquals(0, calls)
    }

    @Test
    fun `provider failures remain safe and locator free`() = runTest {
        listOf(
            OpenSubsonicMediaAccessFailure.ACCOUNT_MISMATCH,
            OpenSubsonicMediaAccessFailure.ACCOUNT_NOT_CONFIGURED,
            OpenSubsonicMediaAccessFailure.CREDENTIAL_UNAVAILABLE,
        ).forEach { failure ->
            val transport = OpenSubsonicRuntimeTransport {
                OpenSubsonicMediaAccessResult.Failure(failure)
            }

            val error = assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    transport.resolveRuntime(
                        recordingId = "recording-1",
                        mediaAssetId = "asset-1",
                        providerKey = "opensubsonic:home",
                    )
                }
            }

            val message = error.message.orEmpty()
            assertFalse(message.contains("server-track-native"))
            assertFalse(message.contains("api-secret"))
            assertFalse(message.contains("apiKey", ignoreCase = true))
            assertFalse(message.contains("password", ignoreCase = true))
        }
    }

    @Test
    fun `unexpected provider exception is redacted before transport boundary`() = runTest {
        val transport = OpenSubsonicRuntimeTransport {
            error("https://music.example/rest/stream.view?id=server-track-native&apiKey=api-secret")
        }

        val error = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                transport.resolveRuntime(
                    recordingId = "recording-1",
                    mediaAssetId = "asset-1",
                    providerKey = "opensubsonic:home",
                )
            }
        }

        assertEquals("Remote playback access unavailable", error.message)
        assertFalse(error.message.orEmpty().contains("server-track-native"))
        assertFalse(error.message.orEmpty().contains("api-secret"))
    }

    @Test
    fun `canonical identity mismatch fails before runtime request leaves provider transport`() = runTest {
        val transport = OpenSubsonicRuntimeTransport {
            OpenSubsonicMediaAccessResult.Ready(runtimeRequest())
        }

        val error = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                transport.resolveRuntime(
                    recordingId = "different-recording",
                    mediaAssetId = "asset-1",
                    providerKey = "opensubsonic:home",
                )
            }
        }

        assertEquals("Canonical remote playback identity mismatch", error.message)
        assertFalse(error.message.orEmpty().contains("server-track-native"))
        assertFalse(error.message.orEmpty().contains("api-secret"))
    }

    private fun runtimeRequest() = OpenSubsonicRuntimeAudioRequest(
        recordingId = "recording-1",
        mediaAssetId = "asset-1",
        providerKey = "opensubsonic:home",
        endpointUrl = "https://music.example/rest/stream.view",
        sourceKey = "server-track-native",
        auth = OpenSubsonicAuth.ApiKey("api-secret"),
        saltFactory = { "fixed-salt" },
    )
}
