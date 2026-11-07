package com.music.bitchord.data.canvas

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.common.StreamKey
import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Provider-neutral Media3 clip store.
 *
 * The existing 150MB LRU absorbs streaming and repeated playback loops. When
 * the listener explicitly opts in, complete HLS or MP4 clips are downloaded
 * into a separate non-evictable Media3 cache (bounded by a hard global quota)
 * under noBackupFilesDir. Both read paths share the same original URL cache
 * keys; offline HLS manifests and segment requests therefore work unchanged.
 * Never claim an incomplete/partially downloaded clip is available offline.
 */
@UnstableApi
object CanvasCache {
    private const val STREAM_CACHE_BYTES = 150L * 1024 * 1024
    private const val SAVED_CACHE_BYTES = 400L * 1024 * 1024
    private const val MAX_CLIP_BYTES = 48L * 1024 * 1024
    private const val PREFS = "visual_canvas_pins_v1"

    private lateinit var streamingCache: SimpleCache
    private lateinit var savedCache: SimpleCache
    private lateinit var context: Context
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val clearEpoch = AtomicLong(0L)

    fun init(context: Context) {
        this.context = context.applicationContext
        val database = StandaloneDatabaseProvider(context)
        streamingCache = SimpleCache(
            File(context.cacheDir, "canvas"),
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES), database,
        )
        savedCache = SimpleCache(
            File(context.noBackupFilesDir, "visual_motion_saved_v1"),
            NoOpCacheEvictor(), database,
        )
    }

    private fun keyFor(url: String): String =
        visualMediaCacheKey(VisualSurface.NOW_PLAYING, url)

    fun isSaved(url: String): Boolean {
        if (!::context.isInitialized || !isSafeVisualMediaUrl(url)) return false
        val key = keyFor(url)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, false)
    }

    /** A pinned HLS clip must play the same rendition downloaded offline.
     * Otherwise the adaptive player may request an uncached higher rendition. */
    fun playbackItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (isSaved(url) && url.substringBefore('?')
                .endsWith(".m3u8", ignoreCase = true)
        ) builder.setStreamKeys(listOf(StreamKey(0, 0)))
        return builder.build()
    }

    /**
     * A completed saved clip wins. An unknown clip still traverses the
     * ordinary LRU and upstream, so default streaming behavior is unchanged.
     * HLS resolves its manifest/variants/segments through this same chain.
     */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory {
        val ordinary = CacheDataSource.Factory()
            .setCache(streamingCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return CacheDataSource.Factory()
            .setCache(savedCache)
            .setUpstreamDataSourceFactory(ordinary)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Called only for visible/selected surfaces and only after user opt-in.
     * Limits both per-item and global bytes, and never records completion
     * before a downloader has finished all selected media segments.
     */
    suspend fun saveForOffline(url: String, surface: VisualSurface): Boolean {
        if (!::context.isInitialized || !isSafeVisualMediaUrl(url)) return false
        val settings = com.music.bitchord.data.settings.AppSettings
        if (!shouldSaveVisualMotion(
                settings.visualRetentionMode.value, surface,
                settings.visualMotionSurfaces.value, settings.meteredConnection.value,
            )) return false
        if (isSaved(url)) return true
        val key = keyFor(url)
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            if (isSaved(url)) return@withLock true
            withContext(Dispatchers.IO) {
                if (savedCache.cacheSpace >= SAVED_CACHE_BYTES) return@withContext false
                // Do not use the streaming-LRU as a write target: its eviction
                // must never invalidate a user's persistent offline choice.
                val transport = CacheDataSource.Factory()
                    .setCache(savedCache)
                    .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Http.client))
                val media = MediaItem.Builder().setUri(url)
                    .apply {
                        if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                            // One rendition only, not every resolution advertised
                            // by the provider's multivariant playlist.
                            setStreamKeys(listOf(StreamKey(0, 0)))
                        }
                    }.build()
                val isHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                val downloader: Downloader = if (isHls)
                    HlsDownloader.Factory(transport).create(media)
                else ProgressiveDownloader(media, transport)
                var oversized = false
                val startEpoch = clearEpoch.get()
                return@withContext try {
                    downloader.download(Downloader.ProgressListener { _, bytes, _ ->
                        if (bytes > MAX_CLIP_BYTES ||
                            savedCache.cacheSpace > SAVED_CACHE_BYTES
                        ) {
                            oversized = true
                            downloader.cancel()
                        }
                    })
                    if (oversized ||
                        savedCache.cacheSpace > SAVED_CACHE_BYTES ||
                        settings.visualRetentionMode.value != VisualRetentionMode.SAVE_ON_WIFI ||
                        startEpoch != clearEpoch.get()
                    ) {
                        downloader.remove()
                        false
                    } else {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(key, true).commit()
                    }
                } catch (cancelled: CancellationException) {
                    downloader.cancel()
                    throw cancelled
                } catch (_: Exception) {
                    runCatching { downloader.remove() }
                    false
                }
            }
        }
    }

    /** Does not touch audio files or Coil's image cache. */
    suspend fun clearSaved() = withContext(Dispatchers.IO) {
        if (!::context.isInitialized) return@withContext
        clearEpoch.incrementAndGet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        savedCache.keys.toList().forEach { key ->
            runCatching { savedCache.removeResource(key) }
        }
    }

    fun savedBytes(): Long = if (::savedCache.isInitialized) savedCache.cacheSpace else 0L
}
