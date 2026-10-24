package com.music.bitchord.acquisition.a0

import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.download.A1AndroidDownloadDestination
import com.music.bitchord.download.A1DownloadDestinationPolicy
import com.music.bitchord.download.A2AndroidAudioDestination
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Local owned fixture only. No Soulseek account, external peer, music catalog,
 * network request or copyright-dependent audio is touched.
 */
@RunWith(AndroidJUnit4::class)
class SoulseekLocalPublicationSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun forgedSafGrantIsRejectedAndDefaultDestinationRemainsSelected() {
        assertTrue(A1AndroidDownloadDestination.reset(context))
        try {
            val forged = Uri.parse("content://com.bitchord.invalid.documents/tree/not-a-grant")
            assertFalse(A1AndroidDownloadDestination.select(context, forged))
            val destination = A1AndroidDownloadDestination.current(context)
            assertTrue(destination is A1DownloadDestinationPolicy.Resolution.Ready)
            assertTrue((destination as A1DownloadDestinationPolicy.Resolution.Ready).target
                is A1DownloadDestinationPolicy.Target.SharedMusic)
        } finally { A1AndroidDownloadDestination.reset(context) }
    }

    @Test fun publishedOwnedWavIsReadableOfflineAndCleanlyRemoved() {
        // MediaStore.Audio default-pending route is intentionally API29+ only.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        assertTrue(A1AndroidDownloadDestination.reset(context))
        val name = "bitchord-instrumented-" + UUID.randomUUID() + ".wav"
        val fixture = File(context.cacheDir, "bitchord-owned-" + UUID.randomUUID() + ".wav")
        // Valid minimum RIFF/WAVE header with a tiny in-memory PCM sample.
        val bytes = ByteArray(48).apply {
            "RIFF".toByteArray().copyInto(this, 0)
            "WAVE".toByteArray().copyInto(this, 8)
            "fmt ".toByteArray().copyInto(this, 12)
            "data".toByteArray().copyInto(this, 36)
        }
        fixture.writeBytes(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val executor = Executors.newSingleThreadExecutor()
        var savedUri: Uri? = null
        try {
            val result = executor.submit<com.music.bitchord.download.A2LocalAudioPublisher.Published> {
                A2AndroidAudioDestination.publishPrivateReceipt(
                    context, fixture, name, bytes.size.toLong(), digest
                )
            }.get(30, TimeUnit.SECONDS)
            savedUri = Uri.parse(result.contentUri)
            assertEquals(bytes.size.toLong(), result.byteCount)
            assertEquals(digest, result.sha256Hex)
            assertEquals("content", savedUri.scheme)
            val offlineBytes = context.contentResolver.openInputStream(savedUri)!!.use { it.readBytes() }
            assertArrayEquals(bytes, offlineBytes)
        } finally {
            savedUri?.let { context.contentResolver.delete(it, null, null) }
            fixture.delete()
            executor.shutdownNow()
            A1AndroidDownloadDestination.reset(context)
        }
    }
}
