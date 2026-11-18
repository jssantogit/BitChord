package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.IOException

/**
 * A virtual bitchord://source URI has no extension. Media3 selects its parser
 * BEFORE the DataSource resolver is opened. Selecting after the resolver
 * would make HLS/MPD bytes appear to ProgressiveMediaSource as audio.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class PreparedSourceMediaSelectionTest {
    private val logical = MediaItem.Builder()
        .setMediaId("source:track-42")
        .setUri("bitchord://source?s=source42&t=track42")
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
            .setTitle("Audio selection").build())
        .build()

    @Test fun manifestTypeIsChosenFromWinningPhysicalUrlBeforeMediaSourceConstruction() {
        val physical = DataSpec.Builder()
            .setUri("https://cdn.example/master.m3u8?sig=a")
            .setHttpRequestHeaders(mapOf("X-Token" to "private"))
            .build()
        val prepared = prepareSourceMediaSelection(logical, physical)
        assertEquals(MimeTypes.APPLICATION_M3U8,
            prepared.item.localConfiguration?.mimeType)
        assertEquals(Uri.parse("https://cdn.example/master.m3u8?sig=a"),
            prepared.item.localConfiguration?.uri)
        assertEquals("source:track-42", prepared.item.mediaId)
        assertEquals(logical.localConfiguration?.uri, prepared.logicalSourceUri)
        assertEquals("Audio selection", prepared.item.mediaMetadata.title.toString())
        assertEquals("private", prepared.resolved.httpRequestHeaders["X-Token"])
    }

    @Test fun dashAndExtensionlessDeclaredHlsAreSelectedWithoutMainThreadNetwork() {
        val dash = prepareSourceMediaSelection(logical,
            DataSpec.Builder().setUri("https://cdn.example/manifest.mpd").build())
        assertEquals(MimeTypes.APPLICATION_MPD,
            dash.item.localConfiguration?.mimeType)
        StreamContainer.declare("https://cdn.example/stream/42", "hls")
        val hls = prepareSourceMediaSelection(logical,
            DataSpec.Builder().setUri("https://cdn.example/stream/42").build())
        assertEquals(MimeTypes.APPLICATION_M3U8,
            hls.item.localConfiguration?.mimeType)
        val audio = prepareSourceMediaSelection(logical,
            DataSpec.Builder().setUri("https://cdn.example/song.flac").build())
        assertNull(audio.item.localConfiguration?.mimeType)
    }

    @Test fun typedPhysicalManifestIsNeverExposedThroughPlayerTimeline() {
        val physical = MediaItem.Builder()
            .setMediaId(logical.mediaId)
            .setUri("https://cdn.example/master.mpd?secret=hidden-in-url")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()
        val underlying = androidx.media3.exoplayer.source.SinglePeriodTimeline(
            1_000_000L, true, false, false, null, physical,
        )
        val timeline = sourceMediaTimeline(underlying, logical)
        val window = timeline.getWindow(0, androidx.media3.common.Timeline.Window())
        assertEquals(logical.mediaId, window.mediaItem.mediaId)
        assertEquals(logical.localConfiguration?.uri,
            window.mediaItem.localConfiguration?.uri)
        assertTrue(window.mediaItem.localConfiguration?.uri.toString()
            .indexOf("secret=hidden") == -1)
        assertEquals(1_000_000L, window.durationUs)
    }

    @Test fun invalidLogicalSourceCannotBecomeASourceSelection() {
        try {
            prepareSourceMediaSelection(MediaItem.fromUri("file:///music/a.flac"),
                DataSpec.Builder().setUri("https://cdn.example/a.mpd").build())
            org.junit.Assert.fail("Non-source item cannot be preselected")
        } catch (_: IOException) { }
    }

    @Test fun noNetworkOrSourceFactoryCallHappensWhileMediaSourceIsCreated() {
        var calls = 0
        val unused = androidx.media3.datasource.DataSource.Factory {
            error("I/O only after preparation")
        }
        val factory = SourceScopedMediaSourceFactory(
            defaultFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(unused),
            createSourceFactory = {
                calls++
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(unused)
            },
            preflightSource = { item ->
                calls++
                prepareSourceMediaSelection(item,
                    DataSpec.Builder().setUri("https://cdn.example/stream.mpd").build())
            },
        )
        val lazyMediaSource = factory.createMediaSource(logical)
        assertTrue(lazyMediaSource is DeferredResolvedSourceMediaSource)
        assertEquals(0, calls)
    }
    @Test fun verifiedOfflineSelectionKeepsLogicalIdentityAndRevocableLease() {
        val owner = ProviderKey("addon", "one")
        val cap = ProviderCapability.EXTENSION_PLAYBACK
        fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(owner, setOf(cap), ProviderReadiness.READY)),
            mapOf(cap to listOf(ProviderChoice(owner, enabled, 0))),
        )
        val gate = ProviderPolicyGate(policy(true))
        val lease = requireNotNull(sourceCacheReadLease("addon", "one", true, gate))
        val cached = prepareCachedProgressiveSourceMediaSelection(logical, lease)
        assertTrue(cached.cachedOffline)
        assertEquals(logical.mediaId, cached.item.mediaId)
        assertEquals(logical.localConfiguration?.uri, cached.item.localConfiguration?.uri)
        assertNull(cached.item.localConfiguration?.mimeType)
        assertEquals(logical.localConfiguration?.uri, cached.resolved.uri)
        assertTrue(cached.playbackLease === lease)
        assertTrue(cached.resolved.httpRequestHeaders.isEmpty())
        val key = "source-segment:addon:one:" + "a".repeat(64)
        val indexed = prepareCachedProgressiveSourceMediaSelection(logical, lease, key)
        assertEquals(key, indexed.item.localConfiguration?.customCacheKey)
        assertEquals(key, indexed.resolved.key)
        assertEquals(logical.localConfiguration?.uri, indexed.item.localConfiguration?.uri)
        assertTrue(indexed.resolved.httpRequestHeaders.isEmpty())
        try {
            prepareCachedProgressiveSourceMediaSelection(logical, lease,
                "https://cdn.example/song.flac?token=secret")
            org.junit.Assert.fail("Unscoped offline key was accepted")
        } catch (_: IOException) {}
        gate.replace(policy(false))
        try {
            prepareCachedProgressiveSourceMediaSelection(logical, lease)
            org.junit.Assert.fail("Revoked offline ticket was reused")
        } catch (_: IOException) {}
    }

    /**
     * Media3 must open a provider-scoped physical cache bucket using only a
     * logical source URI plus opaque customCacheKey. This is the critical
     * offline bridge: no signed URL or previous HTTP headers are reconstructed.
     */
    @Test fun completePhysicalMedia3CacheReplaysThroughLogicalUriWithoutNetwork() {
        val owner = ProviderKey("addon", "one")
        val cap = ProviderCapability.EXTENSION_PLAYBACK
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(owner, setOf(cap), ProviderReadiness.READY)),
            mapOf(cap to listOf(ProviderChoice(owner, true, 0))),
        ))
        val lease = requireNotNull(sourceCacheReadLease("addon", "one", true, gate))
        val key = providerAdaptiveSegmentCacheKey(
            owner, "https://cdn.example/song.flac?secret=ephemeral", null,
            mapOf("Authorization" to "Bearer account-one"), "stable-cache-realm",
        )
        val prepared = prepareCachedProgressiveSourceMediaSelection(logical, lease, key)
        val folder = java.io.File(
            org.robolectric.RuntimeEnvironment.getApplication().cacheDir,
            "source-offline-cache-test-" + java.util.UUID.randomUUID(),
        )
        val cache = androidx.media3.datasource.cache.SimpleCache(
            folder, androidx.media3.datasource.cache.NoOpCacheEvictor(),
            androidx.media3.database.StandaloneDatabaseProvider(
                org.robolectric.RuntimeEnvironment.getApplication()),
        )
        val audio = "ID3---audio-payload-for-offline".toByteArray()
        val keyFactory = androidx.media3.datasource.cache.CacheKeyFactory { spec ->
            scopedOfflineSourceCacheKey(spec.uri.scheme, spec.uri.authority, spec.key)
                ?: spec.key ?: spec.uri.toString()
        }
        fun readAll(source: androidx.media3.datasource.DataSource, spec: DataSpec): ByteArray {
            val result = java.io.ByteArrayOutputStream()
            try {
                source.open(spec)
                val bytes = ByteArray(64)
                while (true) {
                    val count = source.read(bytes, 0, bytes.size)
                    if (count == -1) break
                    result.write(bytes, 0, count)
                }
            } finally { source.close() }
            return result.toByteArray()
        }
        try {
            val writer = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache).setCacheKeyFactory(keyFactory)
                .setUpstreamDataSourceFactory(androidx.media3.datasource.DataSource.Factory {
                    androidx.media3.datasource.ByteArrayDataSource(audio)
                }).createDataSource()
            val physical = DataSpec.Builder().setUri(
                "https://cdn.example/song.flac?secret=ephemeral").setKey(key).build()
            assertTrue(readAll(writer, physical).contentEquals(audio))
            assertTrue(cache.isCached(key, 0, audio.size.toLong()))

            // The offline reader deliberately has no usable network factory.
            val offline = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache).setCacheKeyFactory(keyFactory)
                .setUpstreamDataSourceFactory(androidx.media3.datasource.DataSource.Factory {
                    // CacheDataSource constructs upstream eagerly even when
                    // every byte is cached. Only OPEN must remain unreachable.
                    object : androidx.media3.datasource.DataSource {
                        override fun addTransferListener(
                            listener: androidx.media3.datasource.TransferListener,
                        ) = Unit
                        override fun open(dataSpec: DataSpec): Long =
                            throw AssertionError("Unexpected provider/network resolution")
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                        override fun getUri(): Uri? = null
                        override fun close() = Unit
                    }
                }).createDataSource()
            val local = requireNotNull(prepared.item.localConfiguration)
            val logicalSpec = DataSpec.Builder().setUri(local.uri)
                .setKey(requireNotNull(local.customCacheKey)).build()
            assertTrue(readAll(offline, logicalSpec).contentEquals(audio))
            assertEquals(logical.localConfiguration?.uri, local.uri)
            assertFalse(local.uri.toString().contains("ephemeral"))
        } finally {
            cache.release()
            folder.deleteRecursively()
        }
    }

    @Test fun physicalOfflineIndexKeepsStereoAndAtmosIntentsSeparate() {
        val normal = Uri.parse("bitchord://source?s=source42&t=track42")
        val stereo = normal.buildUpon()
            .appendQueryParameter(
                com.music.bitchord.data.sources.SourceResolver.MIX_PARAM,
                com.music.bitchord.data.sources.SourceResolver.MIX_STEREO,
            ).build()
        val normalKey = AudioCache.logicalSourceCachePartition(normal)
        val stereoKey = AudioCache.logicalSourceCachePartition(stereo)
        assertTrue(normalKey != stereoKey)
        assertTrue(stereoKey.contains("stereo"))
    }

}
